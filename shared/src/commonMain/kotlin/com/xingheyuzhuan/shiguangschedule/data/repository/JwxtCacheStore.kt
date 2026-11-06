package com.xingheyuzhuan.shiguangschedule.data.repository

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import okio.FileSystem
import okio.Path
import kotlin.time.Clock

/**
 * 教务数据本地缓存的通用读写工具。
 *
 * 与 xust-grade-web 的 data/ 目录等价：每类数据按学号落盘一份 JSON，
 * 界面只读缓存，联网抓取由「同步」按钮显式触发。
 */
internal object JwxtCacheStore {

    /**
     * 缓存文件路径：filesDir/<目录>/<前缀><学号>.json
     */
    fun filePath(
        filesDir: Path,
        dirName: String,
        filePrefix: String,
        studentId: String
    ): Path {
        return filesDir / dirName / "$filePrefix$studentId.json"
    }

    /**
     * 读取缓存文本；文件不存在或损坏时返回 null。
     */
    fun readText(fileSystem: FileSystem, path: Path): String? {
        if (!fileSystem.exists(path)) return null

        return try {
            fileSystem.read(path) { readUtf8() }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 写入缓存文本（自动创建父目录）。
     */
    fun writeText(fileSystem: FileSystem, path: Path, text: String) {
        try {
            path.parent?.let { parent ->
                if (!fileSystem.exists(parent)) {
                    fileSystem.createDirectories(parent)
                }
            }
            fileSystem.write(path) { writeUtf8(text) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 删除缓存文件。
     */
    fun delete(fileSystem: FileSystem, path: Path) {
        try {
            if (fileSystem.exists(path)) {
                fileSystem.delete(path)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 生成与 xust-grade-web 一致的抓取时间文本：yyyy-MM-dd HH:mm:ss
     */
    fun nowText(): String {
        val dateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        return buildString {
            append(dateTime.year.toString().padStart(4, '0'))
            append('-')
            append(dateTime.monthNumber.toString().padStart(2, '0'))
            append('-')
            append(dateTime.dayOfMonth.toString().padStart(2, '0'))
            append(' ')
            append(dateTime.hour.toString().padStart(2, '0'))
            append(':')
            append(dateTime.minute.toString().padStart(2, '0'))
            append(':')
            append(dateTime.second.toString().padStart(2, '0'))
        }
    }
}
