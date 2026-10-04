package com.qzkt.timetable.ui.player

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.qzkt.timetable.ui.player.link.mimeTypeOf
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * 下载落盘：把输出流从「存在哪」里拆出来，[com.qzkt.timetable.ui.player.link.MediaDownloader]
 * 只负责往里写字节。
 *
 * - **Android 10（API 29）及以上**：走 MediaStore 写进 `Movies/qzkt/`，
 *   不需要任何存储权限，文件在「相册 / 文件管理」里能看到，卸载应用也还在。
 * - **Android 8 / 9（API 26–28）**：MediaStore 那个无权限入口还不存在，写公开目录要
 *   `WRITE_EXTERNAL_STORAGE`；为了不为了下载一个视频就弹权限，退回应用自己的外部目录
 *   （`Android/data/<包名>/files/Movies`），并把完整路径显示给用户。
 *
 * 下载中途失败或取消时 [Target.abort] 会把半截文件删掉 —— MediaStore 里那条
 * `IS_PENDING` 记录也一并删，不会留一个打不开的残片。
 */
class VideoDownloadStore(private val context: Context) {

    class Target internal constructor(
        val uri: Uri,
        /** 给用户看的落盘位置。 */
        val location: String,
        private val sink: OutputStream,
        private val commit: () -> Unit,
        private val rollback: () -> Unit,
    ) {
        fun open(): OutputStream = sink

        fun finish() {
            runCatching { sink.flush() }
            runCatching { sink.close() }
            commit()
        }

        fun abort() {
            runCatching { sink.close() }
            rollback()
        }
    }

    fun prepare(fileName: String): Target =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) viaMediaStore(fileName) else inAppDir(fileName)

    private fun viaMediaStore(fileName: String): Target {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, mimeTypeOf(fileName))
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + FOLDER)
            // 写完之前标记为 pending，别的应用看不到半成品
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("系统没有让应用写媒体库")
        val sink = resolver.openOutputStream(uri) ?: throw IOException("打不开媒体库里的新文件")
        return Target(
            uri = uri,
            location = Environment.DIRECTORY_MOVIES + "/" + FOLDER + "/" + fileName,
            sink = sink,
            commit = {
                val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            },
            rollback = { runCatching { resolver.delete(uri, null, null) } },
        )
    }

    private fun inAppDir(fileName: String): Target {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)
        return Target(
            uri = Uri.fromFile(file),
            location = file.absolutePath,
            sink = FileOutputStream(file),
            // 让系统媒体库认一下，图库里能刷出来（这个 API 不需要权限）
            commit = { MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null) },
            rollback = { runCatching { file.delete() } },
        )
    }

    private companion object {
        const val FOLDER = "qzkt"
    }
}
