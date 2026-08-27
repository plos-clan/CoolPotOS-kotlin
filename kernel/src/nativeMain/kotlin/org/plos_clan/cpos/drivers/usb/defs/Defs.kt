package org.plos_clan.cpos.drivers.usb.defs

const val DESC_DEVICE: UByte = 1u
const val DESC_CONFIGURATION: UByte = 2u
const val DESC_STRING: UByte = 3u
const val DESC_INTERFACE: UByte = 4u
const val DESC_ENDPOINT: UByte = 5u
const val DESC_DEVICE_QUALIFIER: UByte = 6u
const val DESC_OTHER_SPEED_CONFIG: UByte = 7u
const val DESC_INTERFACE_POWER: UByte = 8u
const val DESC_OTG: UByte = 9u
const val DESC_DEBUG: UByte = 10u
const val DESC_INTERFACE_ASSOCIATION: UByte = 11u

const val DESC_BOS: UByte = 15u
const val DESC_DEVICE_CAPABILITY: UByte = 16u
const val DESC_SS_EP_COMPANION: UByte = 48u

const val DESC_HID: UByte = 0x21u
const val DESC_REPORT: UByte = 0x22u
const val DESC_PHYSICAL: UByte = 0x23u
const val DESC_CS_INTERFACE: UByte = 0x24u
const val DESC_CS_ENDPOINT: UByte = 0x25u

const val CDC_UNION_FUNCTIONAL_DESCRIPTOR: UByte = 0x06u

const val REQ_GET_STATUS: UByte = 0u
const val REQ_CLEAR_FEATURE: UByte = 1u
const val REQ_SET_FEATURE: UByte = 3u
const val REQ_SET_ADDRESS: UByte = 5u
const val REQ_GET_DESCRIPTOR: UByte = 6u
const val REQ_SET_DESCRIPTOR: UByte = 7u
const val REQ_GET_CONFIGURATION: UByte = 8u
const val REQ_SET_CONFIGURATION: UByte = 9u

const val REQ_DIR_IN: UByte = 0x80u
const val REQ_DIR_OUT: UByte = 0x00u
const val REQ_TYPE_STANDARD: UByte = 0x00u
const val REQ_TYPE_CLASS: UByte = 0x20u
const val REQ_TYPE_VENDOR: UByte = 0x40u
const val REQ_REC_DEVICE: UByte = 0x00u
const val REQ_REC_INTERFACE: UByte = 0x01u
const val REQ_REC_ENDPOINT: UByte = 0x02u

const val CLASS_PER_INTERFACE: UByte = 0x00u
const val CLASS_AUDIO: UByte = 0x01u
const val CLASS_COMM: UByte = 0x02u
const val CLASS_HID: UByte = 0x03u
const val CLASS_PHYSICAL: UByte = 0x05u
const val CLASS_IMAGE: UByte = 0x06u
const val CLASS_PRINTER: UByte = 0x07u
const val CLASS_MASS_STORAGE: UByte = 0x08u
const val CLASS_HUB: UByte = 0x09u
const val CLASS_DATA: UByte = 0x0au
const val CLASS_SMART_CARD: UByte = 0x0bu
const val CLASS_VIDEO: UByte = 0x0eu
const val CLASS_HEALTHCARE: UByte = 0x0fu
const val CLASS_DIAGNOSTIC: UByte = 0xdcu
const val CLASS_WIRELESS: UByte = 0xe0u
const val CLASS_MISC: UByte = 0xefu
const val CLASS_VENDOR_SPEC: UByte = 0xffu

const val EP_TYPE_CONTROL: UByte = 0u
const val EP_TYPE_ISO: UByte = 1u
const val EP_TYPE_BULK: UByte = 2u
const val EP_TYPE_INT: UByte = 3u

const val REQ_GET_REPORT: UByte = 0x01u
const val REQ_GET_IDLE: UByte = 0x02u
const val REQ_GET_PROTOCOL: UByte = 0x03u
const val REQ_SET_REPORT: UByte = 0x09u
const val REQ_SET_IDLE: UByte = 0x0au
const val REQ_SET_PROTOCOL: UByte = 0x0bu

const val PROTO_BOOT: UByte = 0u
const val PROTO_REPORT: UByte = 1u

const val SPEED_FULL: UByte = 1u
const val SPEED_LOW: UByte = 2u
const val SPEED_HIGH: UByte = 3u
const val SPEED_SUPER: UByte = 4u
const val SPEED_SUPER_PLUS: UByte = 5u
