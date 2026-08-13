package org.plos_clan.cpos.drivers.usb.unet

const val REQ_SEND_ENCAPSULATED_COMMAND = 0x00u
const val REQ_GET_ENCAPSULATED_RESPONSE = 0x01u

const val RNDIS_PACKET_MSG = 0x0000_0001u
const val RNDIS_INITIALIZE_MSG = 0x0000_0002u
const val RNDIS_HALT_MSG = 0x0000_0003u
const val RNDIS_QUERY_MSG = 0x0000_0004u
const val RNDIS_SET_MSG = 0x0000_0005u
const val RNDIS_RESET_MSG = 0x0000_0006u
const val RNDIS_INDICATE_STATUS = 0x0000_0007u
const val RNDIS_KEEPALIVE_MSG = 0x0000_0008u

const val RNDIS_INITIALIZE_CMPLT = 0x8000_0002u
const val RNDIS_QUERY_CMPLT = 0x8000_0004u
const val RNDIS_SET_CMPLT = 0x8000_0005u
const val RNDIS_RESET_CMPLT = 0x8000_0006u
const val RNDIS_KEEPALIVE_CMPLT = 0x8000_0008u

const val OID_GEN_SUPPORTED_LIST = 0x0001_0101u
const val OID_GEN_HARDWARE_STATUS = 0x0001_0102u
const val OID_GEN_MEDIA_SUPPORTED = 0x0001_0103u
const val OID_GEN_MEDIA_IN_USE = 0x0001_0104u
const val OID_GEN_MAX_FRAME_SIZE = 0x0001_0106u
const val OID_GEN_LINK_SPEED = 0x0001_0107u
const val OID_GEN_TRANSMIT_BLOCK_SIZE = 0x0001_010au
const val OID_GEN_RECEIVE_BLOCK_SIZE = 0x0001_010bu
const val OID_GEN_VENDOR_ID = 0x0001_010cu
const val OID_GEN_VENDOR_DESCRIPTION = 0x0001_010du
const val OID_GEN_CURRENT_PACKET_FILTER = 0x0001_010eu
const val OID_GEN_MAX_TOTAL_SIZE = 0x0001_0111u
const val OID_GEN_MEDIA_CONNECT_STATUS = 0x0001_0114u
const val OID_802_3_PERMANENT_ADDRESS = 0x0101_0101u
const val OID_802_3_CURRENT_ADDRESS = 0x0101_0102u
const val OID_802_3_MULTICAST_LIST = 0x0101_0103u
const val OID_802_3_MAX_LIST_SIZE = 0x0101_0104u

const val RNDIS_PACKET_TYPE_DIRECTED = 0x0000_0001u
const val RNDIS_PACKET_TYPE_MULTICAST = 0x0000_0002u
const val RNDIS_PACKET_TYPE_ALL_MULTICAST = 0x0000_0004u
const val RNDIS_PACKET_TYPE_BROADCAST = 0x0000_0008u
const val RNDIS_PACKET_TYPE_PROMISCUOUS = 0x0000_0020u

const val RNDIS_STATUS_SUCCESS = 0x0000_0000u
const val RNDIS_STATUS_FAILURE = 0xc000_0001u

data class RndisMsgHeader(
    var msgType: UInt = 0u,
    var msgLength: UInt = 0u,
)

data class RndisInitMsg(
    var header: RndisMsgHeader = RndisMsgHeader(),
    var requestId: UInt = 0u,
    var majorVersion: UInt = 0u,
    var minorVersion: UInt = 0u,
    var maxTransferSize: UInt = 0u,
)

data class RndisInitCmplt(
    var header: RndisMsgHeader = RndisMsgHeader(),
    var requestId: UInt = 0u,
    var status: UInt = 0u,
    var majorVersion: UInt = 0u,
    var minorVersion: UInt = 0u,
    var deviceFlags: UInt = 0u,
    var medium: UInt = 0u,
    var maxPacketsPerTransfer: UInt = 0u,
    var maxTransferSize: UInt = 0u,
    var packetAlignFactor: UInt = 0u,
)

data class RndisSetMsg(
    var header: RndisMsgHeader = RndisMsgHeader(),
    var requestId: UInt = 0u,
    var oid: UInt = 0u,
    var infoBufferLength: UInt = 0u,
    var infoBufferOffset: UInt = 0u,
)

data class RndisSetCmplt(
    var header: RndisMsgHeader = RndisMsgHeader(),
    var requestId: UInt = 0u,
    var status: UInt = 0u,
)

data class RndisQueryMsg(
    var header: RndisMsgHeader = RndisMsgHeader(),
    var requestId: UInt = 0u,
    var oid: UInt = 0u,
    var infoBufferLength: UInt = 0u,
    var infoBufferOffset: UInt = 0u,
)

data class RndisQueryCmplt(
    var header: RndisMsgHeader = RndisMsgHeader(),
    var requestId: UInt = 0u,
    var status: UInt = 0u,
    var infoBufferLength: UInt = 0u,
    var infoBufferOffset: UInt = 0u,
)

data class RndisPacketMsg(
    var header: RndisMsgHeader = RndisMsgHeader(),
    var dataOffset: UInt = 0u,
    var dataLength: UInt = 0u,
    var oobDataOffset: UInt = 0u,
    var oobDataLength: UInt = 0u,
    var numOobDataElements: UInt = 0u,
    var perPacketInfoOffset: UInt = 0u,
    var perPacketInfoLength: UInt = 0u,
    var vcHandle: UInt = 0u,
)
