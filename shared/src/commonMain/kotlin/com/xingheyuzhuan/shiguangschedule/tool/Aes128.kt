package com.xingheyuzhuan.shiguangschedule.tool

/**
 * 轻量 AES-128 实现（不依赖任何平台加密库，保证 Kotlin Multiplatform 各端行为一致）。
 *
 * 用途：西安科技大学统一身份认证中心（jwsso）登录页 Vue.encrypt 的等价复刻。
 * 算法为 AES-128-CBC / ZeroPadding，且密钥与 IV 都取自当次请求的 __token__ 前 16 位，
 * 属于教务系统的私有协议，因此不能使用 Keystore 之类的平台实现代替。
 */
object Aes128 {

    private const val BLOCK_SIZE = 16
    private const val KEY_SIZE = 16
    private const val ROUNDS = 10

    private val SBOX = intArrayOf(
        0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
        0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
        0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
        0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
        0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
        0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
        0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
        0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
        0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
        0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
        0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
        0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
        0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
        0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
        0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
        0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16
    )

    /**
     * 轮常量 Rcon[1..14]。
     *
     * 注意：下标 0 存放的是 Rcon[1]（0x01），因此密钥扩展取用时要减一，
     * 即第 i 个 4 字节字对应的常量为 RCON[i / 4 - 1]，写错会导致密文全错。
     * 已用 FIPS-197 官方测试向量（密钥 000102…0f / 明文 001122…ff →
     * 密文 69c4e0d86a7b0430d8cdb78070b4c55a）校验通过。
     */
    private val RCON = intArrayOf(
        0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36, 0x6c, 0xd8, 0xab, 0x4d
    )

    private const val BASE64_TABLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /**
     * AES-128-CBC 加密，不足一块的部分补 0x00（ZeroPadding），与教务系统页面实现保持一致。
     *
     * @param data 明文
     * @param keyAndIv 长度为 16 的密钥，同时作为 IV 使用
     */
    fun encryptCbcWithZeroPadding(data: ByteArray, keyAndIv: ByteArray): ByteArray {
        return encryptCbc(data, keyAndIv, keyAndIv)
    }

    /**
     * AES-128-CBC 加密。
     */
    fun encryptCbc(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(key.size == KEY_SIZE) { "AES-128 密钥长度必须为 $KEY_SIZE 字节" }
        require(iv.size == BLOCK_SIZE) { "CBC 偏移量长度必须为 $BLOCK_SIZE 字节" }

        val roundKeys = expandKey(key)
        val padded = zeroPad(data)
        val result = ByteArray(padded.size)

        var previousBlock = iv.copyOf()
        var offset = 0

        while (offset < padded.size) {
            val block = ByteArray(BLOCK_SIZE)
            for (index in 0 until BLOCK_SIZE) {
                block[index] = (padded[offset + index].toInt() xor previousBlock[index].toInt()).toByte()
            }
            val encrypted = encryptBlock(block, roundKeys)
            encrypted.copyInto(result, offset)
            previousBlock = encrypted
            offset += BLOCK_SIZE
        }

        return result
    }

    /**
     * 标准 Base64 编码（自带实现，避免依赖平台 API）。
     */
    fun encodeBase64(data: ByteArray): String {
        val builder = StringBuilder((data.size + 2) / 3 * 4)

        var index = 0
        while (index < data.size) {
            val remaining = data.size - index
            val b0 = data[index].toInt() and 0xff
            val b1 = if (remaining > 1) data[index + 1].toInt() and 0xff else 0
            val b2 = if (remaining > 2) data[index + 2].toInt() and 0xff else 0

            val triple = (b0 shl 16) or (b1 shl 8) or b2

            builder.append(BASE64_TABLE[(triple shr 18) and 0x3f])
            builder.append(BASE64_TABLE[(triple shr 12) and 0x3f])
            builder.append(if (remaining > 1) BASE64_TABLE[(triple shr 6) and 0x3f] else '=')
            builder.append(if (remaining > 2) BASE64_TABLE[triple and 0x3f] else '=')

            index += 3
        }

        return builder.toString()
    }

    private fun zeroPad(data: ByteArray): ByteArray {
        val remainder = data.size % BLOCK_SIZE
        if (remainder == 0) return data

        return ByteArray(data.size + (BLOCK_SIZE - remainder)).also { padded ->
            data.copyInto(padded)
        }
    }

    private fun expandKey(key: ByteArray): Array<ByteArray> {
        val words = Array((ROUNDS + 1) * 4) { ByteArray(4) }

        for (wordIndex in 0 until 4) {
            for (byteIndex in 0 until 4) {
                words[wordIndex][byteIndex] = key[wordIndex * 4 + byteIndex]
            }
        }

        for (wordIndex in 4 until words.size) {
            val temp = words[wordIndex - 1].copyOf()

            if (wordIndex % 4 == 0) {
                val rotated = temp[0]
                temp[0] = temp[1]
                temp[1] = temp[2]
                temp[2] = temp[3]
                temp[3] = rotated

                for (byteIndex in 0 until 4) {
                    temp[byteIndex] = SBOX[temp[byteIndex].toInt() and 0xff].toByte()
                }

                temp[0] = (temp[0].toInt() xor RCON[wordIndex / 4 - 1]).toByte()
            }

            for (byteIndex in 0 until 4) {
                words[wordIndex][byteIndex] =
                    (words[wordIndex - 4][byteIndex].toInt() xor temp[byteIndex].toInt()).toByte()
            }
        }

        return Array(ROUNDS + 1) { round ->
            val roundKey = ByteArray(BLOCK_SIZE)
            for (column in 0 until 4) {
                for (row in 0 until 4) {
                    roundKey[column * 4 + row] = words[round * 4 + column][row]
                }
            }
            roundKey
        }
    }

    private fun encryptBlock(block: ByteArray, roundKeys: Array<ByteArray>): ByteArray {
        val state = block.copyOf()

        addRoundKey(state, roundKeys[0])

        for (round in 1..ROUNDS) {
            subBytes(state)
            shiftRows(state)
            if (round != ROUNDS) {
                mixColumns(state)
            }
            addRoundKey(state, roundKeys[round])
        }

        return state
    }

    private fun addRoundKey(state: ByteArray, roundKey: ByteArray) {
        for (index in 0 until BLOCK_SIZE) {
            state[index] = (state[index].toInt() xor roundKey[index].toInt()).toByte()
        }
    }

    private fun subBytes(state: ByteArray) {
        for (index in 0 until BLOCK_SIZE) {
            state[index] = SBOX[state[index].toInt() and 0xff].toByte()
        }
    }

    private fun shiftRows(state: ByteArray) {
        val snapshot = state.copyOf()

        for (row in 1 until 4) {
            for (column in 0 until 4) {
                val sourceIndex = ((column + row) % 4) * 4 + row
                state[column * 4 + row] = snapshot[sourceIndex]
            }
        }
    }

    private fun mixColumns(state: ByteArray) {
        for (column in 0 until 4) {
            val base = column * 4

            val a0 = state[base].toInt() and 0xff
            val a1 = state[base + 1].toInt() and 0xff
            val a2 = state[base + 2].toInt() and 0xff
            val a3 = state[base + 3].toInt() and 0xff

            state[base] = (mul2(a0) xor mul3(a1) xor a2 xor a3).toByte()
            state[base + 1] = (a0 xor mul2(a1) xor mul3(a2) xor a3).toByte()
            state[base + 2] = (a0 xor a1 xor mul2(a2) xor mul3(a3)).toByte()
            state[base + 3] = (mul3(a0) xor a1 xor a2 xor mul2(a3)).toByte()
        }
    }

    private fun mul2(value: Int): Int {
        val doubled = (value shl 1) and 0xff
        return if (value and 0x80 != 0) doubled xor 0x1b else doubled
    }

    private fun mul3(value: Int): Int {
        return mul2(value) xor value
    }
}
