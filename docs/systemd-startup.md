2026-09-20 systemd 启动调查

测量环境为 QEMU/KVM、4 vCPU、2 GiB RAM、USB BOT 系统盘和 RNDIS 网络。新旧内核交替启动各三次，每次使用独立的全新 ext4 overlay，并使用同一份 EROFS 用户空间。用户空间已包含本次 DNS 配置修复，因此这组对照衡量的是内核改动，不把 DNS 配置收益计入内核性能提升。测量期间没有并行编译；宿主机仍有其他正常负载。

耗时使用 `multi-user.target.ActiveEnterTimestampMonotonic - Manager.UserspaceTimestampMonotonic`，不使用登录提示出现时间、全部启动作业完成时间或 benchmark 程序开始时间代替。原始日志、内核及 rootfs SHA-256、测量脚本和测试结果保存在 `kernel/build/startup-validation/`。

测量结果：

| systemd → multi-user.target | 第一次 | 第二次 | 第三次 | 中位数 |
| --- | ---: | ---: | ---: | ---: |
| 修改前 | 37.852 s | 31.491 s | 34.603 s | 34.603 s |
| 修改后 | 31.545 s | 23.116 s | 23.344 s | 23.344 s |

中位数减少 **11.258 秒（32.5%）**。三次最终启动均完成 DHCP 和实际 DNS 查询，未再出现 resolved 激活失败、DNS 插件等待、sd-login monitor ENOENT 或地址更新 EEXIST。仍然存在的失败单元是 systemd-update-utmp.service。样本较少且宿主有负载，不将该比例视为所有硬件上的固定收益。

六次性能测量使用保存的 `measured-kernel.elf.gz`；随后补齐了 Netlink echo 回复与实际地址属性的一致性，并重新构建及测试。最终镜像另作一次功能冷启动检查，该次检查与测试编译并行，不混入性能统计。页缓存和 DNS 实现没有在测量后改变。

实现与原因：

| 因素 | 根因与证据 | 处理 |
| --- | --- | --- |
| 文件访问成本随全局缓存增长 | `PageCache.invalidate()` 原先遍历所有缓存页、所有正在读取的页，并重建整条 CLOCK 队列。FUSE 不带 KEEP_CACHE 的打开、文件写入和块设备写入都会触发 | 按缓存身份组织驻留页与在途读取；回收队列使用可按对象移除的有序集合。整文件失效只扫描所属对象；短范围失效直接索引页，只在大范围时遍历所属对象。保留读写并发时的失效标记及引用计数 |
| DNS 插件等待 | NetworkManager 默认附加向 resolved 推送 DNS，即使主插件已是 `dns=default`；本 rootfs 未启用 resolved，旧日志出现 D-Bus 激活失败和 DNS 插件未就绪 | 仅设置 `systemd-resolved=false`，保留发行版默认 DNS 插件、resolv.conf 管理方式和文件 |
| 地址更新误报重复 | `RTM_NEWADDR` 忽略 `NLM_F_REPLACE`，已有地址一律返回 EEXIST | 在协议层解释 REPLACE/EXCL；网络层接受已有地址的更新并通知监听者。排他创建和普通重复创建仍返回 EEXIST，不重复添加地址，不重复发送首次配置的 ARP 通告 |
| 旧诊断镜像中的 tmpfiles 长耗时和目录缺失 | 旧诊断 overlay 的 `/upper` 属于宿主 UID 1000，overlay 根目录继承该所有权。tmpfiles 输出 `Detected unsafe path transition / (owned by 1000)`，反复查询 userdb 并拒绝创建 `/run/utmp`、`/run/systemd/machines` 等路径；后者又使 sd-login monitor 返回 ENOENT | 诊断镜像改用与正式 `Ext4ImageTask` 相同的 fakeroot 方式生成。正式构建原本已正确设置所有权，不增加运行时补丁。前期错误所有权镜像的 29.94/18.63 秒数据不作为有效性能对照 |

性能测量版本的独立关键路径诊断启动为 22.909 秒，logind 保持 active/running、NRestarts=0：

| 关键路径阶段 | 相对 systemd 的开始时间 | 阶段耗时 |
| --- | ---: | ---: |
| tmpfiles-setup-dev-early | 2.265 s | 3.482 s |
| tmpfiles-setup-dev | 5.754 s | 0.184 s |
| tmpfiles-setup | 6.003 s | 7.413 s |
| D-Bus broker | 13.947 s | 0.385 s |
| NetworkManager | 14.410 s | 6.746 s |
| user-sessions | 21.199 s | 1.671 s |
| multi-user.target | 22.909 s | — |

因此启动尚非瞬时完成，剩余关键路径仍是运行时目录初始化、服务加载及 IPC。没有把未单独测量的调度或解压成本冒充已确认的独立耗时，也没有绕过 tmpfiles 的实际工作。

页缓存的失效复杂度从遍历全局缓存，变为遍历所属对象的在途读取和 `min(所属对象页数, 区间页数 × 缓存种类数)`；回收队列仅删除实际失效页。没有改变 FUSE 缓存有效期，没有忽略失效通知，也没有通过禁用服务、缩短超时或伪造系统调用成功来获得启动改善。

启动期间的非 OK 输出按原因归类如下。正常的条件跳过、设备不存在时跳过模块服务，以及“正在启动”进度不算失败。以下保留项没有被屏蔽。

| 输出 | 分析及状态 |
| --- | --- |
| resolved 激活失败、DNS plugin did not become ready | 已修复配置；验证实际 DNS 查询 |
| NetworkManager 地址更新 `File exists` | 已修复 REPLACE/EXCL 语义；真实 Netlink 请求回归覆盖创建、更新、排他冲突和地址唯一性 |
| sd-login monitor `-2` | 错误所有权诊断镜像未创建运行时目录；正确生成镜像后检查目录与日志 |
| UTMP 服务失败 | 内核 fcntl 未实现 POSIX 记录锁，返回 EINVAL；错误诊断镜像还缺少 `/run/utmp`。正确所有权不能替代文件锁实现，仍保留真实失败 |
| getty `Failed to lock /dev/console` | flock 未实现，返回 ENOSYS；需要完整的开放文件描述共享、阻塞等待与最终关闭释放语义 |
| `Failed to get CPU usage` | cgroupfs 缺少 cpu.stat。尤其在 systemd-analyze 查询所有单元时集中出现；不能用全零统计代替真实 CPU 记账 |
| `fs.file-max`、`fs.nr_open` 缺失 | proc sysctl 未提供这些资源限制接口；需要与实际文件描述及全局文件资源计数关联 |
| autofs4 模块缺失 | 内核没有对应的 autofs 支持；没有以空模块冒充实现 |
| root shared mount propagation 不支持 | 挂载传播语义未实现；不能将标志设置直接返回成功 |
| BPF LSM、BPF/cgroup firewall 不支持 | 缺少对应执行和权限控制机制；仍报告能力缺失 |
| ProtectHostname、PrivateNetwork 降级 | 相应 UTS/network namespace 能力缺失；不删除服务的保护配置 |
| tmpfs usrquota 不可用 | 不支持该配额选项，systemd 按 graceful-option 规则忽略 |
| `System is tainted: old-kernel` | uname release 使用项目版本名称，不能按 Linux 版本号判断能力；不伪造 Linux 版本以绕过探测 |
| D-Bus auxiliary groups 回退到 NSS | SO_PEERGROUPS 缺失。正确实现需要连接时的凭据快照，不能按 PID 临时查询替代 |
| `/usr/sbin/ethtool` 缺失、carrier detection 不支持 | udev 的 NetworkManager 规则尝试通过 ethtool 补充驱动身份；同时内核缺少相应网络 ioctl/驱动元数据，单装程序不足以解决 |
| IPv4 forwarding 默认值缺失 | 缺少 `/proc/sys/net/ipv4/conf/.../forwarding` 及对应转发控制；没有增加不影响实际网络行为的假 sysctl |
| journal stdout 连接 `Broken pipe` | 基线中出现的瞬时日志连接失败；未单独定位其并发根因，不宣称已修复 |
| audit、peer pidfd 的调试提示 | UTMP 手动 debug 诊断中探测到的可选接口缺失；与服务最终因文件锁失败区分 |
| `/usr/local/.../getent` 的 ELF open 失败 | 诊断命令执行过程中的 PATH 候选查找，随后 `/usr/bin/getent` 成功；不属于 DNS 解析失败 |

验证覆盖缓存的局部失效、不同对象隔离、在途读取与预读失效、错误读取后的清理、回收时保留已引用页，以及实际 Netlink socket 请求（包含 echo 与实际地址属性一致性）、磁盘访问和 VFS 行为。JVM 测试 264 项，0 失败、0 错误，1 项因缺少外部 ACPI 固件表跳过；最终 QEMU 测试 18 项全部通过。

执行的验证命令：

```sh
./gradlew :kernel:buildImage
./gradlew :kernel:jvmTest :kernel:qemuTest -PqemuFilter='PageCacheTest|RouteAddressTest|NativeMemoryTest|StorageDiskTest|VfsCoreSemanticsTest|FuseAbiTest'
bash -n assets/init assets/userland.sh
git diff --check
```

生产内核、rootfs 和磁盘镜像构建成功。另行尝试的聚合 `build` 暴露了原有 common metadata 编译问题：`commonMain/.../block/Device.kt:96` 的 `OutOfMemoryError` 无法解析；本次使用实际内核和 JVM/QEMU 构建任务完成验证，没有修改该无关实现。Gradle 原有的 configuration-cache 序列化警告仍存在。

没有修改 mlibc，没有新增 C 代码或代码注释。已有 mlibc 工作区差异保留。

后续配置与代码复核：

`docker.io/cachyos/cachyos:latest` 是构建容器；`assets/userland.sh` 在新的临时目录中通过 `pacman --root` 安装指定包，再生成 EROFS，并非直接启动 CachyOS 完整安装镜像。容器本身没有安装 NetworkManager。生成的 rootfs 中 NetworkManager 为 1.58.1-1，发行版配置目录仅有 `20-connectivity.conf`；filesystem 2025.10.12-1 的包清单将 `/etc/resolv.conf` 记录为普通文件，systemd 安装脚本也未启用 resolved。旧启动日志明确记录 `dns=default,systemd-resolved rc-manager=symlink`，因此先前关于 resolved 符号链接触发自动选择的解释没有证据，应予更正。

[NetworkManager 1.58 手册](https://github.com/NetworkManager/NetworkManager/blob/1.58.0/man/NetworkManager.conf.xml) 说明：`main.systemd-resolved` 默认为 true，是主 DNS 插件之外的附加同步开关；`rc-manager=symlink` 对普通文件或不存在的 `/etc/resolv.conf` 仍直接写入。问题并非已证实的 CachyOS 特有不一致，而是本项目选择的服务组合与该默认附加行为不匹配。删除冗余的 `dns=default`、`rc-manager=file` 以及删除重建 resolv.conf 的操作，仅保留 `systemd-resolved=false`。

页缓存失效逻辑收拢到 `CachedSource`，将范围相交判断放到缓存键；通过提前返回消除分支嵌套。预读直接构建有界列表，拆开缓存键、加载对象和集合登记；协议标志使用具名掩码。地址删除直接返回接口与已删除地址，消除跨锁作用域的可空临时变量。本节修改晚于上述性能测量，原有数字不作为本次重构的新测量结果。

精简 DNS 配置后的功能冷启动日志为 `kernel/build/startup-validation/refined.log`：NetworkManager 记录 `dns=default rc-manager=symlink`，自动写入 `nameserver 10.0.2.3`，`getent ahostsv4 example.com` 成功；未出现 resolved 激活失败或 DNS 插件等待。该次启动与测试编译并行，不计入性能对照。

本次重构重新构建镜像成功，QEMU 回归 19 项全部通过，包含新增的预读偏移溢出边界测试；`bash -n assets/userland.sh` 与 `git diff --check` 通过。构建与测试记录分别为 `refined-build.log`、`refined-native-results.json`。
