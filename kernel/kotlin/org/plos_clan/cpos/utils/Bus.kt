package org.plos_clan.cpos.utils

object VTModeConstants {
    const val KDGETMODE = 0x4B3B // 获取终端模式命令
    const val KDSETMODE = 0x4B3A // 设置终端模式命令

    const val KD_TEXT = 0x00     // 文本模式
    const val KD_GRAPHICS = 0x01 // 图形模式


    const val KDGKBMODE = 0x4B44 // gets current keyboard mode
    const val KDSKBMODE = 0x4B45 // sets current keyboard mode

    const val K_RAW = 0x00       // 原始模式（未处理扫描码）
    const val K_XLATE = 0x01     // 转换模式（生成 ASCII）
    const val K_MEDIUMRAW = 0x02 // 中等原始模式
    const val K_UNICODE = 0x03   // Unicode 模式

    const val VT_OPENQRY = 0x5600   // get next available vt
    const val VT_GETMODE = 0x5601   // get mode of active vt
    const val VT_SETMODE = 0x5602

    const val VT_GETSTATE = 0x5603
    const val VT_SENDSIG = 0x5604

    const val VT_ACTIVATE = 0x5606   // make vt active
    const val VT_WAITACTIVE = 0x5607 // wait for vt active
}

object TermiosConstants {
    const val VINTR = 0
    const val VQUIT = 1
    const val VERASE = 2
    const val VKILL = 3
    const val VEOF = 4
    const val VTIME = 5
    const val VMIN = 6
    const val VSWTC = 7
    const val VSTART = 8
    const val VSTOP = 9
    const val VSUSP = 10
    const val VEOL = 11
    const val VREPRINT = 12
    const val VDISCARD = 13
    const val VWERASE = 14
    const val VLNEXT = 15
    const val VEOL2 = 16

    const val IGNBRK = 0x0000001
    const val BRKINT = 0x0000002
    const val IGNPAR = 0x0000004
    const val PARMRK = 0x0000010
    const val INPCK = 0x0000020
    const val ISTRIP = 0x0000040
    const val INLCR = 0x0000100
    const val IGNCR = 0x0000200
    const val ICRNL = 0x0000400
    const val IUCLC = 0x0001000
    const val IXON = 0x0002000
    const val IXANY = 0x0004000
    const val IXOFF = 0x0010000
    const val IMAXBEL = 0x0020000
    const val IUTF8 = 0x0040000

    const val OPOST = 0x000001
    const val OLCUC = 0x000002
    const val ONLCR = 0x000004
    const val OCRNL = 0x000008
    const val ONOCR = 0x000010
    const val ONLRET = 0x000020
    const val OFILL = 0x000040
    const val OFDEL = 0x000080

    const val CSIZE = 0x000030
    const val CS5 = 0x000000
    const val CS6 = 0x000010
    const val CS7 = 0x000020
    const val CS8 = 0x000030
    const val CSTOPB = 0x000040
    const val CREAD = 0x000080
    const val PARENB = 0x000100
    const val PARODD = 0x000200
    const val HUPCL = 0x000400
    const val CLOCAL = 0x000800

    const val ISIG = 0x000001
    const val ICANON = 0x000002
    const val ECHO = 0x000008
    const val ECHOE = 0x000010
    const val ECHOK = 0x000020
    const val ECHONL = 0x000040
    const val NOFLSH = 0x000080
    const val TOSTOP = 0x000100
    const val IEXTEN = 0x010000
}

object PollEvents {
    const val POLLIN = 0x0001
    const val POLLPRI = 0x0002
    const val POLLOUT = 0x0004
    const val POLLERR = 0x0008
    const val POLLHUP = 0x0010
    const val POLLNVAL = 0x0020
    const val POLLRDNORM = 0x0040
    const val POLLRDBAND = 0x0080
    const val POLLWRNORM = 0x0100
    const val POLLWRBAND = 0x0200
    const val POLLRDHUP = 0x2000

    const val NORMAL_INPUT = POLLIN or POLLRDNORM
    const val NORMAL_OUTPUT = POLLOUT or POLLWRNORM
    const val DEFAULT_FILE_EVENTS = NORMAL_INPUT or NORMAL_OUTPUT
    const val UNCONDITIONALLY_REPORTED = POLLERR or POLLHUP or POLLNVAL
}

object Errno {
    const val ERRNO_MASK: ULong = 0x7FFFFFFFFFFFF000uL

    const val EOK = 0

    const val EPERM = 1
    const val ENOENT = 2
    const val ESRCH = 3
    const val EINTR = 4
    const val EIO = 5
    const val ENXIO = 6
    const val E2BIG = 7
    const val ENOEXEC = 8
    const val EBADF = 9
    const val ECHILD = 10
    const val EAGAIN = 11
    const val ENOMEM = 12
    const val EACCES = 13
    const val EFAULT = 14
    const val ENOTBLK = 15
    const val EBUSY = 16
    const val EEXIST = 17
    const val EXDEV = 18
    const val ENODEV = 19
    const val ENOTDIR = 20
    const val EISDIR = 21
    const val EINVAL = 22
    const val ENFILE = 23
    const val EMFILE = 24
    const val ENOTTY = 25
    const val ETXTBSY = 26
    const val EFBIG = 27
    const val ENOSPC = 28
    const val ESPIPE = 29
    const val EROFS = 30
    const val EMLINK = 31
    const val EPIPE = 32
    const val EDOM = 33
    const val ERANGE = 34
    const val EDEADLK = 35
    const val ENAMETOOLONG = 36
    const val ENOLCK = 37
    const val ENOSYS = 38
    const val ENOTEMPTY = 39
    const val ELOOP = 40

    const val EWOULDBLOCK = EAGAIN

    const val ENOMSG = 42
    const val EIDRM = 43
    const val ECHRNG = 44
    const val EL2NSYNC = 45
    const val EL3HLT = 46
    const val EL3RST = 47
    const val ELNRNG = 48
    const val EUNATCH = 49
    const val ENOCSI = 50
    const val EL2HLT = 51
    const val EBADE = 52
    const val EBADR = 53
    const val EXFULL = 54
    const val ENOANO = 55
    const val EBADRQC = 56
    const val EBADSLT = 57

    const val EDEADLOCK = EDEADLK

    const val EBFONT = 59
    const val ENOSTR = 60
    const val ENODATA = 61
    const val ETIME = 62
    const val ENOSR = 63
    const val ENONET = 64
    const val ENOPKG = 65
    const val EREMOTE = 66
    const val ENOLINK = 67
    const val EADV = 68
    const val ESRMNT = 69
    const val ECOMM = 70
    const val EPROTO = 71
    const val EMULTIHOP = 72
    const val EDOTDOT = 73
    const val EBADMSG = 74
    const val EOVERFLOW = 75
    const val ENOTUNIQ = 76
    const val EBADFD = 77
    const val EREMCHG = 78
    const val ELIBACC = 79
    const val ELIBBAD = 80
    const val ELIBSCN = 81
    const val ELIBMAX = 82
    const val ELIBEXEC = 83
    const val EILSEQ = 84
    const val ERESTART = 85
    const val ESTRPIPE = 86
    const val EUSERS = 87
    const val ENOTSOCK = 88
    const val EDESTADDRREQ = 89
    const val EMSGSIZE = 90
    const val EPROTOTYPE = 91
    const val ENOPROTOOPT = 92
    const val EPROTONOSUPPORT = 93
    const val ESOCKTNOSUPPORT = 94
    const val EOPNOTSUPP = 95

    const val ENOTSUP = EOPNOTSUPP

    const val EPFNOSUPPORT = 96
    const val EAFNOSUPPORT = 97
    const val EADDRINUSE = 98
    const val EADDRNOTAVAIL = 99
    const val ENETDOWN = 100
    const val ENETUNREACH = 101
    const val ENETRESET = 102
    const val ECONNABORTED = 103
    const val ECONNRESET = 104
    const val ENOBUFS = 105
    const val EISCONN = 106
    const val ENOTCONN = 107
    const val ESHUTDOWN = 108
    const val ETOOMANYREFS = 109
    const val ETIMEDOUT = 110
    const val ECONNREFUSED = 111
    const val EHOSTDOWN = 112
    const val EHOSTUNREACH = 113
    const val EALREADY = 114
    const val EINPROGRESS = 115
    const val ESTALE = 116
    const val EUCLEAN = 117
    const val ENOTNAM = 118
    const val ENAVAIL = 119
    const val EISNAM = 120
    const val EREMOTEIO = 121
    const val EDQUOT = 122
    const val ENOMEDIUM = 123
    const val EMEDIUMTYPE = 124
    const val ECANCELED = 125
    const val ENOKEY = 126
    const val EKEYEXPIRED = 127
    const val EKEYREVOKED = 128
    const val EKEYREJECTED = 129
    const val EOWNERDEAD = 130
    const val ENOTRECOVERABLE = 131
    const val ERFKILL = 132
    const val EHWPOISON = 133
}
