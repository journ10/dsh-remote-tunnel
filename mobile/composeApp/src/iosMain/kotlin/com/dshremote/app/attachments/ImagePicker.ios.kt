package com.dshremote.app.attachments

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.Foundation.NSData
import platform.Foundation.base64EncodedStringWithOptions
import platform.darwin.NSObject

@Composable
actual fun rememberImagePicker(onPicked: (PickedImage) -> Unit): ImagePickerHandle {
    val delegate = remember { ImagePickerDelegate(onPicked) }
    return remember(delegate) {
        object : ImagePickerHandle {
            override fun pickImage() {
                val controller = UIImagePickerController()
                controller.sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary
                controller.delegate = delegate
                topViewController()?.presentViewController(controller, animated = true, completion = null)
            }
        }
    }
}

private class ImagePickerDelegate(
    private val onPicked: (PickedImage) -> Unit,
) : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
    override fun imagePickerController(
        picker: UIImagePickerController,
        didFinishPickingMediaWithInfo: Map<Any?, *>,
    ) {
        val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
        picker.dismissViewControllerAnimated(true, completion = null)
        if (image == null) return
        val data: NSData = UIImagePNGRepresentation(image) ?: return
        onPicked(
            PickedImage(
                mediaType = "image/png",
                base64 = data.base64EncodedStringWithOptions(0u),
                name = null,
            ),
        )
    }

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
        picker.dismissViewControllerAnimated(true, completion = null)
    }
}

private fun topViewController(): UIViewController? {
    var top = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}
