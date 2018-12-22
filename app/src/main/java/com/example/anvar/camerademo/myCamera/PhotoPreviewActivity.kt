package com.example.anvar.camerademo.myCamera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import com.example.anvar.camerademo.R
import kotlinx.android.synthetic.main.activity_photo_preview.*
import java.io.File

class PhotoPreviewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_preview)
        intent.extras?.let {
            val intentArguments = it.get(PhotoCameraActivity.INTENT_IMAGE_ARGUMENT_KEY)
            if (intentArguments != null) {
                setImageViewIcon(intentArguments.toString())
            }
        }
    }

    private fun setImageViewIcon(imageFilePath: String) {
        val imageFile = File(imageFilePath)
        if (imageFile.exists()) {
            val display = windowManager.defaultDisplay
            val size = Point().also(display::getSize)
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
            photoImageView.setImageBitmap(Bitmap.createScaledBitmap(bitmap, size.x, size.y,false))
        }
    }

}