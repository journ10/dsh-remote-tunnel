package com.dshremote.app.attachments

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.darwin.NSObject

@Composable
actual fun rememberFilePicker(onPicked: (PickedFile) -> Unit): FilePickerHandle {
    val delegate = remember { FilePickerDelegate(onPicked) }
    return remember(delegate) {
        object : FilePickerHandle {
            override fun pickFile() {
                val controller = UIDocumentPickerViewController(
                    documentTypes = listOf("public.data"),
                    inMode = UIDocumentPickerMode.UIDocumentPickerModeImport,
                )
                controller.delegate = delegate
                topViewController()?.presentViewController(controller, animated = true, completion = null)
            }
        }
    }
}

private class FilePickerDelegate(
    private val onPicked: (PickedFile) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        controller.dismissViewControllerAnimated(true, completion = null)
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
        val access = url.startAccessingSecurityScopedResource()
        try {
            val data: NSData = NSData.dataWithContentsOfURL(url) ?: return
            onPicked(
                PickedFile(
                    name = url.lastPathComponent ?: "file",
                    base64 = data.base64EncodedStringWithOptions(0u),
                    bytes = data.length.toLong(),
                ),
            )
        } finally {
            if (access) url.stopAccessingSecurityScopedResource()
        }
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        controller.dismissViewControllerAnimated(true, completion = null)
    }
}

private fun topViewController(): UIViewController? {
    var top = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}
