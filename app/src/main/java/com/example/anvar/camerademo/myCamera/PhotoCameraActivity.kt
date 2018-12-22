package com.example.anvar.camerademo.myCamera

import android.annotation.SuppressLint

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.support.v7.app.AppCompatActivity
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import com.example.anvar.camerademo.R
import kotlinx.android.synthetic.main.activity_photo_camera.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sign

class PhotoCameraActivity : AppCompatActivity() {

    private var cameraRotationState = CameraType.BACK

    private var isFlashSupported: Boolean? = null

    private var firstStart = true

    private var isTorchOn = false

    private var imageSize: Size? = null

    private var cameraId: String? = null

    private var imageFolder: File? = null

    private var previewSize: Size? = null

    private var totalRotation: Int? = null

    private var imageFileName: String? = null

    private var imageReader: ImageReader? = null

    private var backgroundHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null

    private var backgroundHandlerThread: HandlerThread? = null

    private var previewCaptureSession: CameraCaptureSession? = null

    private var captureRequestBuilder: CaptureRequest.Builder? = null

    private lateinit var cameraTextureView: AutoFitTextureView

    private val onImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        backgroundHandler!!.post(ImageSaver(reader.acquireLatestImage()))
    }

    private val previewCaptureCallback: CameraCaptureSession.CaptureCallback =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                super.onCaptureCompleted(session, request, result)
                startStillCaptureRequest()
                Toast.makeText(applicationContext, "Хопа", Toast.LENGTH_SHORT).show()
            }
        }

    private val cameraDeviceStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            startPreview()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            cameraDevice = null
        }
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            checkCameraRotationStateAndSetUpCameraId(width, height)
            connectCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {

        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            return false
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {

        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_camera)
        cameraTextureView = findViewById(R.id.cameraTextureView)
        createImageFolder("CameraImageFolder")
        photoImageButton.setOnClickListener {
            lockFocus()
        }
        switchRotationButton.setOnClickListener {
            reverseCamera()
        }
        flash.setOnClickListener {
            switchFlash()
        }
    }

    override fun onResume() {
        super.onResume()
        startCamera()
    }

    override fun onPause() {
        super.onPause()
        disconnectCamera()
    }

    private fun lockFocus() {
        try {
            captureRequestBuilder!!.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            previewCaptureSession!!.capture(captureRequestBuilder!!.build(), previewCaptureCallback, backgroundHandler)
        } catch (ex: CameraAccessException) {
            ex.printStackTrace()
        }
    }

    private fun reverseCamera() {
        cameraRotationState = when (cameraRotationState) {
            CameraType.BACK -> CameraType.FRONT
            CameraType.FRONT -> CameraType.BACK
        }
        restartCamera()
    }

    private fun switchFlash() {
        isTorchOn = if (isTorchOn) {
            flash.setImageResource(R.mipmap.ic_flash_off_white_24dp)
            false
        } else {
            flash.setImageResource(R.mipmap.ic_flash_auto_white_24dp)
            true
        }
        restartCamera()
    }

    private fun restartCamera() {
        disconnectCamera()
        startCamera()
    }

    private fun startCamera() {
        startBackgroundThread()
        if (cameraTextureView.isAvailable) {
            checkCameraRotationStateAndSetUpCameraId(cameraTextureView.width, cameraTextureView.height)
            connectCamera(cameraTextureView.width, cameraTextureView.height)
        } else {
            cameraTextureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    private fun checkCameraRotationStateAndSetUpCameraId(width: Int, height: Int) {
        if (cameraRotationState == CameraType.FRONT) {
            setUpCameraId(width, height, CameraType.FRONT)
        } else {
            setUpCameraId(width, height)
        }
    }

    private fun setUpCameraId(width: Int, height: Int, cameraType: CameraType = CameraType.BACK) {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        loop@ for (cameraId in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val cameraDirection = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (cameraDirection != null) {
                when (cameraType) {
                    CameraType.BACK -> {
                        if (cameraDirection == CameraCharacteristics.LENS_FACING_FRONT) {
                            continue@loop
                        }
                    }
                    CameraType.FRONT -> {
                        if (cameraDirection == CameraCharacteristics.LENS_FACING_BACK) {
                            continue@loop
                        }
                    }
                }
            }
            if (cameraType == CameraType.BACK) {
                isFlashSupported = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
            }
            setPreviewSizes(characteristics, width, height)
            imageReader = ImageReader.newInstance(imageSize!!.width, imageSize!!.height, ImageFormat.JPEG, 1)
            imageReader!!.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
            this.cameraId = cameraId
            return
        }
        throw IllegalStateException("Could not set Camera Id")
    }

    private fun setPreviewSizes(characteristics: CameraCharacteristics, width: Int, height: Int) {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val deviceOrientation = windowManager.defaultDisplay.rotation
        totalRotation = sensorToDeviceRotation(
            characteristics,
            deviceOrientation
        )
        val swapRotation = totalRotation == 90 || totalRotation == 270
        var rotationWidth = width
        var rotationHeight = height
        if (swapRotation) {
            rotationWidth = height
            rotationHeight = width
        }
        previewSize = chooseOptimalSize(
            map.getOutputSizes(
                SurfaceTexture::class.java
            ), rotationWidth, rotationHeight
        )
        cameraTextureView.setAspectRatio(previewSize!!.height, previewSize!!.width)
        imageSize = chooseOptimalSize(
            map.getOutputSizes(ImageFormat.JPEG), rotationWidth, rotationHeight
        )
    }

    @SuppressLint("MissingPermission")
    private fun connectCamera(width: Int, height: Int) {
        transformImage(width, height)
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            cameraManager.openCamera(cameraId!!, cameraDeviceStateCallback, backgroundHandler)
        } catch (ex: CameraAccessException) {
            ex.printStackTrace()
        }
    }

    private fun disconnectCamera() {
        closeCamera()
        stopBackgroundThread()
    }

    private fun closeCamera() {
        if (cameraDevice != null) {
            cameraDevice!!.close()
            cameraDevice = null
        }
    }

    private fun startPreview() {
        val surfaceTexture = cameraTextureView.surfaceTexture
        surfaceTexture.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)
        val previewSurface = Surface(surfaceTexture)
        val captureStateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                previewCaptureSession = session
                try {
                    previewCaptureSession!!.setRepeatingRequest(
                        captureRequestBuilder!!.build(),
                        null,
                        backgroundHandler
                    )
                } catch (ex: CameraAccessException) {
                    ex.printStackTrace()
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Toast.makeText(applicationContext, "Ну сорян чо", Toast.LENGTH_SHORT).show()
            }
        }
        try {
            captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder!!.addTarget(previewSurface)
            setAutoFlash()
            cameraDevice!!.createCaptureSession(
                Arrays.asList(previewSurface, imageReader!!.surface),
                captureStateCallback
                , null
            )
        } catch (ex: CameraAccessException) {
            ex.printStackTrace()
        }
    }

    private fun setAutoFlash() {
        try {
            if (cameraRotationState == CameraType.BACK) {
                if (isFlashSupported!!) {
                    if (isTorchOn) {
                        captureRequestBuilder!!.set(
                            CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                        )
                    } else if(!firstStart) {
                        captureRequestBuilder!!.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        captureRequestBuilder!!.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    }
                    firstStart = false
                }
            }
        } catch (ex: CameraAccessException) {
            ex.printStackTrace()
        }
    }

    private fun startBackgroundThread() {
        backgroundHandlerThread = HandlerThread("CameraDemo")
        backgroundHandlerThread!!.start()
        backgroundHandler = Handler(backgroundHandlerThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundHandlerThread?.quitSafely()
        try {
            backgroundHandlerThread?.join()
            backgroundHandlerThread = null
            backgroundHandler = null
        } catch (ex: InterruptedException) {
            ex.printStackTrace()
        }
    }

    private fun startStillCaptureRequest() {
        try {
            captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureRequestBuilder!!.addTarget(imageReader!!.surface)
            captureRequestBuilder!!.set(
                CaptureRequest.CONTROL_AF_MODE,
                captureRequestBuilder!!.get(CaptureRequest.CONTROL_AF_MODE)
            )
            captureRequestBuilder!!.set(CaptureRequest.JPEG_ORIENTATION, totalRotation)
            val stillCaptureCallback: CameraCaptureSession.CaptureCallback =
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureStarted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        timestamp: Long,
                        frameNumber: Long
                    ) {
                        super.onCaptureStarted(session, request, timestamp, frameNumber)
                        try {
                            createImageFileName()
                        } catch (ex: IOException) {
                            ex.printStackTrace()
                        }
                    }
                }
            previewCaptureSession!!.capture(captureRequestBuilder!!.build(), stillCaptureCallback, null)
        } catch (ex: CameraAccessException) {
            ex.printStackTrace()
        }
    }

    private fun createImageFolder(imageFolderName: String) {
        val imageFile = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        imageFolder = File(imageFile, imageFolderName)
        if (!imageFolder!!.exists()) {
            imageFolder!!.mkdirs()
        }
    }

    private fun createImageFileName(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val prepend = "IMAGE_${timestamp}_"
        val imageFile = File.createTempFile(prepend, ".jpg", imageFolder)
        imageFileName = imageFile.absolutePath
        return imageFile
    }

    private fun transformImage(width: Int, height: Int) {
        if (previewSize != null) {
            val matrix = Matrix()
            val previewSizeWidth = previewSize!!.width
            val previewSizeHeight = previewSize!!.height
            val rotation = windowManager.defaultDisplay.rotation
            val textureRectF = RectF(0f, 0f, width.toFloat(), height.toFloat())
            val previewRectF = RectF(0f, 0f, previewSizeHeight.toFloat(), previewSizeWidth.toFloat())
            val centerX = textureRectF.centerX()
            val centerY = textureRectF.centerY()
            if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
                previewRectF.offset(centerX - previewRectF.centerX(), centerY - previewRectF.centerY())
                matrix.setRectToRect(textureRectF, previewRectF, Matrix.ScaleToFit.FILL)
                val scale = Math.max(
                    (height.toFloat() / previewSizeHeight.toFloat()),
                    width.toFloat() / previewSizeWidth.toFloat()
                )
                matrix.setScale(scale, scale, centerX, centerY)
                matrix.postRotate(90 * (rotation.toFloat() - 2), centerX, centerY)
            } else if (rotation == Surface.ROTATION_180) {
                matrix.postRotate(180f, centerX, centerY)
            }
            cameraTextureView.setTransform(matrix)
        }
    }

    private fun sensorToDeviceRotation(cameraCharacteristics: CameraCharacteristics, deviceOrientation: Int): Int {
        val sensorDeviceOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
        setOrientations()
        val newDeviceOrientation = ORIENTATIONS.get(deviceOrientation)
        return (sensorDeviceOrientation + newDeviceOrientation) % 360
    }

    private fun chooseOptimalSize(choices: Array<Size>, width: Int, height: Int): Size {
        val bigEnough = mutableListOf<Size>()
        for (option in choices) {
            if (option.width == option.height * height / width && option.width >= width && option.height >= height) {
                bigEnough.add(option)
            }
        }
        return if (bigEnough.size > 0) {
            Collections.min(bigEnough, CompareSizeByArea)
        } else {
            choices[0]
        }
    }

    private fun setOrientations() {
        if (cameraRotationState == CameraType.BACK) {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 180)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 0)
        }
        if (cameraRotationState == CameraType.FRONT) {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }
    }

    companion object {

        private val ORIENTATIONS = SparseIntArray()

        const val INTENT_IMAGE_ARGUMENT_KEY = "photoImage"

    }

    private enum class CameraType {
        FRONT,
        BACK
    }

    private object CompareSizeByArea : Comparator<Size> {
        override fun compare(o1: Size?, o2: Size?): Int {
            return sign(o1!!.width.toDouble() * o1.height.toDouble() / o2!!.width.toDouble() * o2.height.toDouble()).toInt()
        }
    }

    private inner class ImageSaver(private val mImage: Image) : Runnable {
        override fun run() {
            val byteBuffer = mImage.planes[0].buffer
            val bytes = ByteArray(byteBuffer.remaining())
            byteBuffer.get(bytes)
            var fileOutputStream: FileOutputStream? = null
            try {
                fileOutputStream = FileOutputStream(imageFileName)
                fileOutputStream.write(bytes)
            } catch (ex: IOException) {
                ex.printStackTrace()
            } finally {
                mImage.close()
                try {
                    fileOutputStream?.close()
                } catch (ex: IOException) {
                    ex.printStackTrace()
                }
            }
        }
    }
}
