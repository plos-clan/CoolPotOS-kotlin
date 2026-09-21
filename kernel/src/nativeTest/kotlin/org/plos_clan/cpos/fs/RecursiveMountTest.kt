package org.plos_clan.cpos.fs

import org.plos_clan.cpos.fs.tmpfs.Tmpfs
import org.plos_clan.cpos.fs.vfs.FileSystemContext
import org.plos_clan.cpos.fs.vfs.VfsPath
import org.plos_clan.cpos.fs.vfs.FileMode
import org.plos_clan.cpos.fs.vfs.FileSystemConfiguration
import org.plos_clan.cpos.fs.vfs.MountAttributeUpdate
import org.plos_clan.cpos.fs.vfs.MountFlags
import org.plos_clan.cpos.fs.vfs.MountPropagation
import org.plos_clan.cpos.fs.vfs.NodeCreation
import org.plos_clan.cpos.fs.vfs.NodeKind
import org.plos_clan.cpos.fs.vfs.UnmountMode
import org.plos_clan.cpos.fs.vfs.Vfs
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class RecursiveMountTest {
    @Test
    fun preservesRuntimeSubmountsAcrossRootReplacement() {
        val tree = Tree()
        try {
            tree.mkdir("/run", "/sysroot")
            tree.mount("/run")
            tree.mount("/sysroot")
            tree.mkdir("/run/lower", "/run/store", "/sysroot/run")
            tree.mount("/run/store")
            tree.mkdir("/run/store/upper")
            tree.bind("/", "/run/lower", false)
            tree.bind("/run", "/sysroot/run", true)
            val original = tree.path("/run/store/upper")
            val copied = tree.path("/sysroot/run/store/upper")
            assertSame(original.inode, copied.inode)
            assertNotSame(original.mount, copied.mount)
            tree.unmount("/run")
            assertSame(copied.inode, tree.path("/sysroot/run/store/upper").inode)
            val source = VfsPathname.fromString("/sysroot")
            val destination = VfsPathname.fromString("/")
            assertIs<VfsResult.Ok<Unit>>(tree.vfs.moveMount(
                tree.caller, tree.context, source, destination,
            ))
            assertIs<VfsResult.Ok<Unit>>(tree.vfs.chroot(tree.caller, tree.context, destination))
            assertSame(copied.inode, tree.path("/run/store/upper").inode)
        } finally {
            tree.context.release()
        }
    }

    @Test
    fun recursiveBindRestrictsSubtreesAndPrunesUnbindableMounts() {
        val tree = Tree()
        try {
            tree.mkdir("/source", "/outside", "/copy", "/plain")
            tree.mkdir("/source/child", "/source/hidden")
            tree.mount("/outside")
            tree.mount("/source/child")
            tree.mount("/source/hidden")
            tree.mkdir("/source/child/nested")
            tree.mount("/source/child/nested")
            val attributes = MountAttributeUpdate(
                MountFlags.NONE, MountFlags.NONE, MountPropagation.UNBINDABLE,
            )
            assertIs<VfsResult.Ok<Unit>>(tree.vfs.setMountAttributes(
                tree.context, tree.path("/source/hidden"), attributes, false,
            ))
            tree.bind("/source", "/copy", true)
            tree.bind("/source", "/plain", false)
            assertSame(tree.path("/source/child/nested").inode, tree.path("/copy/child/nested").inode)
            assertSame(tree.path("/copy").mount, tree.path("/copy/hidden").mount)
            assertSame(tree.path("/plain").mount, tree.path("/plain/child").mount)
            assertNotSame(tree.path("/source/child").mount, tree.path("/copy/child").mount)
        } finally {
            tree.context.release()
        }
    }

    private class Tree {
        val vfs = Vfs()
        val caller = VfsOperationContext.KERNEL
        val context = run {
            assertIs<VfsResult.Ok<Unit>>(vfs.register(Tmpfs))
            assertIs<VfsResult.Ok<FileSystemContext>>(
                vfs.createContext(Tmpfs.name),
            ).value
        }

        fun mkdir(vararg paths: String) {
            val node = NodeCreation(NodeKind.Directory, FileMode(0x1edu))
            for (path in paths) {
                val pathname = VfsPathname.fromString(path)
                assertIs<VfsResult.Ok<*>>(vfs.createNode(caller, context, pathname, node))
            }
        }

        fun mount(path: String) {
            val pathname = VfsPathname.fromString(path)
            val configuration = FileSystemConfiguration(Tmpfs.name)
            assertIs<VfsResult.Ok<Unit>>(vfs.mount(caller, context, pathname, configuration))
        }

        fun bind(source: String, target: String, recursive: Boolean) {
            val from = VfsPathname.fromString(source)
            val to = VfsPathname.fromString(target)
            assertIs<VfsResult.Ok<Unit>>(vfs.bindMount(caller, context, from, to, recursive))
        }

        fun unmount(path: String) {
            val pathname = VfsPathname.fromString(path)
            assertIs<VfsResult.Ok<Unit>>(vfs.unmount(caller, context, pathname, UnmountMode.DETACH))
        }

        fun path(path: String): VfsPath {
            val pathname = VfsPathname.fromString(path)
            return assertIs<VfsResult.Ok<VfsPath>>(
                vfs.resolve(caller, context, pathname),
            ).value
        }
    }
}
