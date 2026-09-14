import argparse
import base64
import hashlib
import json
import math
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field


@dataclass
class VerificationReport:
    mode: str
    expected: int = 0
    results: dict = field(default_factory=dict)
    errors: list = field(default_factory=list)
    active: str | None = None
    finished: bool = False
    metadata: dict = field(default_factory=dict)

    def read(self, log: Path):
        for line in log.read_text(errors="replace").splitlines():
            if not line.startswith("CPOS\t"):
                continue
            try:
                event, *values = [base64.b64decode(value, validate=True).decode() for value in line.split("\t")[1:]]
                self.accept(event, values)
            except (ValueError, UnicodeError, IndexError, KeyError) as error:
                self.errors.append(f"Invalid verification record: {line}: {error}")
        if not self.finished or self.active or len(self.results) != self.expected or self.expected <= 0:
            self.errors.append(f"Incomplete verification: {len(self.results)}/{self.expected}, active={self.active}")

    def accept(self, event, values):
        if not (not self.finished):
            raise ValueError("Record after completion")
        if event == "begin":
            mode, expected, version = values
            if not (mode == self.mode and self.expected == 0):
                raise ValueError(f"Invalid {event} record")
            self.expected = int(expected)
            if not (self.expected > 0):
                raise ValueError(f"Invalid {event} record")
            self.metadata["kotlin"] = version
        elif event == "settings":
            warmups, iterations, duration = map(int, values)
            if not (self.mode == "benchmark" and self.expected > 0 and self.active is None):
                raise ValueError(f"Invalid {event} record")
            if not (warmups >= 0 and iterations > 0 and duration > 0 and "iterations" not in self.metadata):
                raise ValueError(f"Invalid {event} record")
            self.metadata.update(warmups=warmups, iterations=iterations, iterationNanoseconds=duration)
        elif event == "error":
            self.errors.append(values[0])
        elif event == "end":
            completed, failures = map(int, values)
            if not (not self.active and completed == self.expected == len(self.results)):
                raise ValueError(f"Invalid {event} record")
            if not (failures == sum(result["status"] == "fail" for result in self.results.values()) + len(self.errors)):
                raise ValueError(f"Invalid {event} record")
            self.finished = True
        elif event == "start":
            name, = values
            if not (self.expected > 0 and self.active is None and name not in self.results):
                raise ValueError(f"Invalid {event} record")
            self.active = name
            self.results[name] = {"name": name, "status": "running", "samples": []}
        else:
            name, *data = values
            if not (name == self.active):
                raise ValueError(f"Invalid {event} record")
            result = self.results[name]
            if event == "sample":
                index, operations, nanoseconds = map(int, data)
                if not (self.mode == "benchmark" and index == len(result["samples"]) and operations > 0 and nanoseconds > 0):
                    raise ValueError(f"Invalid {event} record")
                result["samples"].append({"operations": operations, "nanoseconds": nanoseconds, "nsPerOp": nanoseconds / operations})
            elif event in ("pass", "skip", "fail"):
                result["status"] = event
                if event == "fail":
                    result["failure"] = data[0]
                else:
                    result["nanoseconds"] = int(data[0])
                    if not (result["nanoseconds"] >= 0):
                        raise ValueError(f"Invalid {event} record")
                if not (self.mode != "benchmark" or event != "pass" or len(result["samples"]) == self.metadata["iterations"]):
                    raise ValueError(f"Invalid {event} record")
                self.active = None
            else:
                raise ValueError(f"Unknown event {event}")

    def write(self, output: Path, metadata):
        document = {"mode": self.mode, "metadata": metadata | self.metadata,
                    "expected": self.expected, "results": list(self.results.values()), "errors": self.errors}
        (output / "results.json").write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n")
        suite = ET.Element("testsuite", name=f"qemu{self.mode.capitalize()}")
        for result in self.results.values():
            group, _, name = result["name"].rpartition(".")
            case = ET.SubElement(suite, "testcase", classname=group, name=name, time=str(result.get("nanoseconds", 0) / 1e9))
            if result["status"] == "skip":
                ET.SubElement(case, "skipped")
            elif result["status"] == "fail":
                ET.SubElement(case, "failure").text = result["failure"]
            elif result["status"] == "running":
                ET.SubElement(case, "error").text = "Guest did not finish this case"
        for error in self.errors:
            ET.SubElement(ET.SubElement(suite, "testcase", classname="qemu", name="infrastructure"), "error").text = error
        suite.set("tests", str(len(suite)))
        for attribute, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
            suite.set(attribute, str(len(suite.findall(f"testcase/{tag}"))))
        invalid = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\ufffe\uffff]")
        for element in suite.iter():
            element.attrib.update({key: invalid.sub("\ufffd", value) for key, value in element.attrib.items()})
            if element.text is not None:
                element.text = invalid.sub("\ufffd", element.text)
        ET.indent(suite)
        ET.ElementTree(suite).write(output / "junit.xml", encoding="utf-8", xml_declaration=True)


class QemuRunner:
    def __init__(self, mode, output, timeout, command, kernel=None, build_type=None, gc=None):
        self.report = VerificationReport(mode)
        self.output = output
        self.timeout = timeout
        self.command = command
        self.metadata = {"buildType": build_type, "gc": gc}
        if kernel is not None:
            with kernel.open("rb") as stream:
                self.metadata["kernelSha256"] = hashlib.file_digest(stream, "sha256").hexdigest()

    def run(self):
        self.output.mkdir(parents=True, exist_ok=True)
        log = self.output / "serial.log"
        start = time.monotonic()
        returncode = None
        with log.open("wb") as stream:
            try:
                with subprocess.Popen(self.command, stdin=subprocess.DEVNULL, stdout=stream,
                                      stderr=subprocess.STDOUT, start_new_session=True) as process:
                    try:
                        returncode = process.wait(timeout=self.timeout)
                    finally:
                        if process.poll() is None:
                            os.killpg(process.pid, signal.SIGKILL)
                            process.wait()
            except (OSError, subprocess.TimeoutExpired) as error:
                self.report.errors.append(str(error))
        self.report.read(log)
        if returncode != 33:
            self.report.errors.append(f"QEMU exit status: {returncode}, expected 33")
        metadata = self.metadata | {"command": self.command, "returncode": returncode, "elapsedSeconds": time.monotonic() - start}
        for key, command in (("commit", ["git", "rev-parse", "HEAD"]), ("qemu", [self.command[0], "--version"])):
            try:
                metadata[key] = subprocess.check_output(command, text=True, stderr=subprocess.DEVNULL, timeout=10).strip()
            except (OSError, subprocess.SubprocessError):
                metadata[key] = None
        self.report.write(self.output, metadata)
        failed = self.report.errors or any(result["status"] not in ("pass", "skip") for result in self.report.results.values())
        print(f"QEMU {self.report.mode}: {len(self.report.results)}/{self.report.expected} cases, {'FAILED' if failed else 'PASSED'}; {self.output}")
        for error in self.report.errors:
            print(error, file=sys.stderr)
        return int(bool(failed))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("test", "benchmark"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--timeout", type=float, default=600)
    parser.add_argument("--kernel", type=Path)
    parser.add_argument("--build-type", choices=("debug", "release"))
    parser.add_argument("--gc")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    arguments = parser.parse_args()
    command = arguments.command[1:] if arguments.command[:1] == ["--"] else arguments.command
    if not command or not math.isfinite(arguments.timeout) or arguments.timeout <= 0:
        parser.error("A QEMU command and a positive timeout are required")
    sys.exit(QemuRunner(arguments.mode, arguments.output, arguments.timeout, command,
                       arguments.kernel, arguments.build_type, arguments.gc).run())
