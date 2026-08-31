package org.plos_clan.cpos.network

import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.utils.LittleEndianBuffer

internal data class GenericNetlinkOperation(
    val command: Int,
    val flags: Int,
)

internal data class GenericNetlinkMulticastGroup(
    val id: Int,
    val name: String,
)

internal data class RegisteredGenericNetlinkFamily(
    val id: Int,
    val family: GenericNetlinkFamily,
    val multicastGroups: List<GenericNetlinkMulticastGroup>,
)

internal data class GenericNetlinkRequest(
    val netlink: NetlinkRequest,
    val family: RegisteredGenericNetlinkFamily,
    val command: Int,
    val version: Int,
    val userHeader: NetlinkBuffer,
    val attributes: NetlinkAttributes,
)

internal abstract class GenericNetlinkFamily(
    val name: String,
    val version: Int,
    val headerSize: Int,
    val maximumAttribute: Int,
    val operations: List<GenericNetlinkOperation>,
) {
    init {
        require(name.isNotEmpty() && name.encodeToByteArray().size < GENL_NAMSIZ)
        require(version in 0..UByte.MAX_VALUE.toInt() && headerSize >= 0 && maximumAttribute >= 0)
        require(operations.all { it.command in 0..UByte.MAX_VALUE.toInt() && it.flags >= 0 })
        require(operations.map(GenericNetlinkOperation::command).distinct().size == operations.size)
    }

    abstract fun handle(request: GenericNetlinkRequest): NetlinkResult

    companion object {
        private const val GENL_NAMSIZ = 16
    }
}

internal object GenericNetlinkProtocol : NetlinkKernelProtocol(NetlinkProtocolKind.GENERIC) {
    private val families: List<RegisteredGenericNetlinkFamily>

    init {
        val registered = mutableListOf<RegisteredGenericNetlinkFamily>()
        val controller = GenericNetlinkController(registered)
        registered += RegisteredGenericNetlinkFamily(
            GENL_ID_CTRL,
            controller,
            listOf(GenericNetlinkMulticastGroup(GENL_ID_CTRL, CTRL_MCGRP_NAME)),
        )
        families = registered.toList()
    }

    override val multicastGroupCount: Int
        get() = families.maxOfOrNull { registered ->
            registered.multicastGroups.maxOfOrNull(GenericNetlinkMulticastGroup::id) ?: 0
        } ?: 0

    override fun handle(request: NetlinkRequest): NetlinkResult {
        val message = request.message
        if (message.payload.size < GENL_HEADER_SIZE) {
            return NetlinkResult.Failure(VfsError.INVALID_ARGUMENT, "Generic Netlink header is missing")
        }
        val registered = families.firstOrNull { it.id == message.type.toInt() }
            ?: return NetlinkResult.Failure(
                VfsError.NOT_FOUND,
                "Generic Netlink family ${message.type} is not registered",
            )
        val family = registered.family
        val command = message.payload.readU8(0).toInt()
        val operation = family.operations.firstOrNull { it.command == command }
            ?: return NetlinkResult.Failure(
                VfsError.NOT_SUPPORTED,
                "Generic Netlink command $command is not supported by ${family.name}",
            )
        val dumping = message.flags.toInt() and NetlinkAbi.NLM_F_DUMP == NetlinkAbi.NLM_F_DUMP
        if (dumping && operation.flags and GENL_CMD_CAP_DUMP == 0 ||
            !dumping && operation.flags and GENL_CMD_CAP_DO == 0
        ) {
            return NetlinkResult.Failure(VfsError.NOT_SUPPORTED)
        }
        if (operation.flags and (GENL_ADMIN_PERM or GENL_UNS_ADMIN_PERM) != 0 &&
            !hasNetworkAdmin(request.process)
        ) {
            return NetlinkResult.Failure(VfsError.NOT_PERMITTED)
        }
        if (message.payload.readU16(2) != 0.toUShort()) {
            return NetlinkResult.Failure(VfsError.INVALID_ARGUMENT, "Reserved header field must be zero")
        }
        val attributeOffset = GENL_HEADER_SIZE + family.headerSize
        val userHeader = message.payload.slice(GENL_HEADER_SIZE, family.headerSize)
            ?: return NetlinkResult.Failure(VfsError.INVALID_ARGUMENT, "Family header is truncated")
        val attributes = message.attributes(attributeOffset)
            ?: return NetlinkResult.Failure(VfsError.INVALID_ARGUMENT, "Malformed Generic Netlink attributes")
        return family.handle(
            GenericNetlinkRequest(
                request,
                registered,
                command,
                message.payload.readU8(1).toInt(),
                userHeader,
                attributes,
            ),
        )
    }

    private const val GENL_HEADER_SIZE = 4
    private const val GENL_ID_CTRL = 0x10
    private const val CTRL_MCGRP_NAME = "notify"
    private const val GENL_ADMIN_PERM = 0x01
    private const val GENL_CMD_CAP_DO = 0x02
    private const val GENL_CMD_CAP_DUMP = 0x04
    private const val GENL_UNS_ADMIN_PERM = 0x10
}

private class GenericNetlinkController(
    private val families: List<RegisteredGenericNetlinkFamily>,
) : GenericNetlinkFamily(
    name = "nlctrl",
    version = CTRL_VERSION,
    headerSize = 0,
    maximumAttribute = 0,
    operations = listOf(
        GenericNetlinkOperation(
            CTRL_CMD_GETFAMILY,
            GENL_CMD_CAP_DO or GENL_CMD_CAP_DUMP or GENL_CMD_CAP_HASPOL,
        ),
    ),
) {
    override fun handle(request: GenericNetlinkRequest): NetlinkResult {
        if (request.command != CTRL_CMD_GETFAMILY) {
            return NetlinkResult.Failure(VfsError.NOT_SUPPORTED)
        }
        val idAttribute = request.attributes[CTRL_ATTR_FAMILY_ID]
        val nameAttribute = request.attributes[CTRL_ATTR_FAMILY_NAME]
        val id = idAttribute?.u16()?.toInt()
        if (idAttribute != null && id == null) {
            return invalidAttribute(request, idAttribute, "Family ID must be a 16-bit value")
        }
        val name = nameAttribute?.string(GENL_NAMSIZ)
        if (nameAttribute != null && name == null) {
            return invalidAttribute(request, nameAttribute, "Family name is not a valid NUL string")
        }
        val dumping = request.netlink.message.flags.toInt() and NetlinkAbi.NLM_F_DUMP ==
            NetlinkAbi.NLM_F_DUMP
        if (!dumping && id == null && name == null) {
            return NetlinkResult.Failure(
                VfsError.INVALID_ARGUMENT,
                "CTRL_CMD_GETFAMILY requires a family ID or name",
            )
        }
        val selected = if (id == null && name == null) {
            families
        } else {
            val matches = families.filter { registered ->
                (id == null || registered.id == id) &&
                    (name == null || registered.family.name == name)
            }
            if (matches.isEmpty()) {
                return NetlinkResult.Failure(VfsError.NOT_FOUND, "Generic Netlink family was not found")
            }
            matches
        }
        return NetlinkResult.Success(
            selected.map(::familyReply),
            multipart = dumping,
        )
    }

    private fun invalidAttribute(
        request: GenericNetlinkRequest,
        attribute: NetlinkAttributeView,
        message: String,
    ): NetlinkResult.Failure = NetlinkResult.Failure(
        VfsError.INVALID_ARGUMENT,
        message,
        attribute.offset - request.netlink.message.raw.offset,
    )

    private fun familyReply(registered: RegisteredGenericNetlinkFamily): NetlinkReply {
        val family = registered.family
        val attributes = mutableListOf(
            NetlinkAttribute.string(CTRL_ATTR_FAMILY_NAME, family.name),
            NetlinkAttribute.u16(CTRL_ATTR_FAMILY_ID, registered.id.toUShort()),
            NetlinkAttribute.u32(CTRL_ATTR_VERSION, family.version.toUInt()),
            NetlinkAttribute.u32(CTRL_ATTR_HDRSIZE, family.headerSize.toUInt()),
            NetlinkAttribute.u32(CTRL_ATTR_MAXATTR, family.maximumAttribute.toUInt()),
        )
        if (family.operations.isNotEmpty()) {
            attributes += NetlinkAttribute.nested(
                CTRL_ATTR_OPS,
                family.operations.mapIndexed { index, operation ->
                    NetlinkAttribute.nested(
                        index + 1,
                        listOf(
                            NetlinkAttribute.u32(CTRL_ATTR_OP_ID, operation.command.toUInt()),
                            NetlinkAttribute.u32(CTRL_ATTR_OP_FLAGS, operation.flags.toUInt()),
                        ),
                        marked = false,
                    )
                },
                marked = false,
            )
        }
        if (registered.multicastGroups.isNotEmpty()) {
            attributes += NetlinkAttribute.nested(
                CTRL_ATTR_MCAST_GROUPS,
                registered.multicastGroups.mapIndexed { index, group ->
                    NetlinkAttribute.nested(
                        index + 1,
                        listOf(
                            NetlinkAttribute.u32(CTRL_ATTR_MCAST_GRP_ID, group.id.toUInt()),
                            NetlinkAttribute.string(CTRL_ATTR_MCAST_GRP_NAME, group.name),
                        ),
                        marked = false,
                    )
                },
                marked = false,
            )
        }
        val header = ByteArray(GENL_HEADER_SIZE)
        LittleEndianBuffer(header).apply {
            writeU8(0, CTRL_CMD_NEWFAMILY.toUByte())
            writeU8(1, version.toUByte())
            writeU16(2, 0u)
        }
        return NetlinkReply(
            GENL_ID_CTRL,
            NetlinkCodec.payload(header, attributes),
        )
    }

    companion object {
        private const val GENL_HEADER_SIZE = 4
        private const val GENL_NAMSIZ = 16
        private const val GENL_ID_CTRL = 0x10
        private const val GENL_CMD_CAP_DO = 0x02
        private const val GENL_CMD_CAP_DUMP = 0x04
        private const val GENL_CMD_CAP_HASPOL = 0x08
        private const val CTRL_VERSION = 2
        private const val CTRL_CMD_NEWFAMILY = 1
        private const val CTRL_CMD_GETFAMILY = 3
        private const val CTRL_ATTR_FAMILY_ID = 1
        private const val CTRL_ATTR_FAMILY_NAME = 2
        private const val CTRL_ATTR_VERSION = 3
        private const val CTRL_ATTR_HDRSIZE = 4
        private const val CTRL_ATTR_MAXATTR = 5
        private const val CTRL_ATTR_OPS = 6
        private const val CTRL_ATTR_MCAST_GROUPS = 7
        private const val CTRL_ATTR_OP_ID = 1
        private const val CTRL_ATTR_OP_FLAGS = 2
        private const val CTRL_ATTR_MCAST_GRP_NAME = 1
        private const val CTRL_ATTR_MCAST_GRP_ID = 2
    }
}
