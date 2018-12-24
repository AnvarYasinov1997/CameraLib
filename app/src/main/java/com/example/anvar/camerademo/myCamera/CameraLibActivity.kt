package com.example.anvar.camerademo.myCamera

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.View
import com.example.anvar.camerademo.R
import kotlinx.android.synthetic.main.activity_photo_camera.*

class CameraLibActivity : AppCompatActivity(), View.OnClickListener {

    private lateinit var getCamera: GetCamera

    private lateinit var textureView: AutoFitTextureView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_camera)
        textureView = cameraTextureView
        getCamera = GetCameraImpl(textureView) {
            when (it) {
                FlashAction.ON -> {
                    flash.setImageResource(R.mipmap.ic_flash_auto_white_24dp)

                }
                FlashAction.OFF -> {
                    flash.setImageResource(R.mipmap.ic_flash_off_white_24dp)

                }
            }
        }
        photoImageButton.setOnClickListener(this)
        switchRotationButton.setOnClickListener(this)
        flash.setOnClickListener(this)

    }

    override fun onStart() {
        super.onStart()
        getCamera.sendAction(UiAction.START)
    }

    override fun onResume() {
        super.onResume()
        getCamera.sendAction(UiAction.RESUME)
    }

    override fun onPause() {
        super.onPause()
        getCamera.sendAction(UiAction.PAUSE)
    }

    override fun onStop() {
        super.onStop()
        getCamera.sendAction(UiAction.STOP)
    }

    override fun onDestroy() {
        super.onDestroy()
        getCamera.sendAction(UiAction.DESTROY)
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.photoImageButton -> getCamera.sendAction(UiAction.TAKE_PHOTO)
            R.id.switchRotationButton -> getCamera.sendAction(UiAction.SWITCH_CAMERA)
            R.id.flash -> getCamera.sendAction(UiAction.SWITCH_FLASH)
        }
    }
}