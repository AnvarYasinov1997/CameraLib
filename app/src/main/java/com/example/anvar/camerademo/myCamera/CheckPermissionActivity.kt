package com.example.anvar.camerademo.myCamera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.support.v7.app.AppCompatActivity

class CheckPermissionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPermissions()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!permissions.isEmpty()) {
            if (requestCode == REQUEST_PERMISSION_RESULT) {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    val photoCameraActivityIntent = Intent(this, PhotoCameraActivity::class.java)
                    startActivity(photoCameraActivityIntent)
                } else {
                    finish()
                }
            }
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                REQUEST_PERMISSION_RESULT
            )
        }
    }

    companion object {
        private const val REQUEST_PERMISSION_RESULT = 0
    }

}