package com.example.anvar.camerademo.sample

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.*
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Chronometer
import android.widget.ImageButton
import android.widget.Toast
import com.example.anvar.camerademo.R
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sign

class MainActivity : AppCompatActivity() {

    private var mCaptureState = STATE_WAIT_LOCK

    private var mVideoSize: Size? = null

    private var mImageSize: Size? = null

    private var mCameraId: String? = null

    private var mPreviewSize: Size? = null

    private var mVideoFolder: File? = null

    private var mImageFolder: File? = null

    private var mTotalRotation: Int? = null

    private var mIsRecording: Boolean = false

    private var mVideoFileName: String? = null

    private var mImageFileName: String? = null

    private var mChronometer: Chronometer? = null

    private var mImageReader: ImageReader? = null

    private lateinit var mTextureView: TextureView

    private var mBackgroundHandler: Handler? = null

    private var mCameraDevice: CameraDevice? = null

    private var mMediaRecorder: MediaRecorder? = null

    private var mRecordImageButton: ImageButton? = null

    private var mStillImageButton: ImageButton? = null

    private var mBackgroundHandlerThread: HandlerThread? = null

    private var mPreviewCaptureSession: CameraCaptureSession? = null

    private var mCaptureRequestBuilder: CaptureRequest.Builder? = null

    private val mPreviewCaptureCallback: CameraCaptureSession.CaptureCallback =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                super.onCaptureCompleted(session, request, result)
                process(result)
            }

            private fun process(captureResult: CaptureResult) {
                when (mCaptureState) {
                    STATE_PREVIEW -> {
                        // Do something
                    }
                    STATE_WAIT_LOCK -> {
                        mCaptureState = STATE_PREVIEW
                        var afState = captureResult.get(CaptureResult.CONTROL_AF_STATE)
                        if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                            Toast.makeText(applicationContext, "Хопа", Toast.LENGTH_SHORT).show()
                            startStillCaptureRequest()
                        }
                    }
                }
            }
        }


    private val mOnImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        mBackgroundHandler!!.post(ImageSaver(reader.acquireLatestImage()))
    }

    private val mCameraDeviceStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            mCameraDevice = camera
            if (mIsRecording) {
                try {
                    createVideoFileName()
                } catch (ex: IOException) {
                    ex.printStackTrace()
                }
                startRecord()
                Thread.sleep(500)
                mMediaRecorder!!.start()
            } else {
                startPreview()
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            mCameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            mCameraDevice = null
        }
    }

    private val mSurfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            setupCamera(width, height)
            connectCamera()
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
        setContentView(R.layout.activity_main)
        createVideoFolder()
        createImageFolder()
        mMediaRecorder = MediaRecorder()
        mChronometer = chronometer
        mTextureView = textureView
        mRecordImageButton = videoOnlineImageButton
        mStillImageButton = cameraImageButton2
        mRecordImageButton!!.setOnClickListener {
            if (mIsRecording) {
                mChronometer!!.stop()
                mChronometer!!.visibility = View.INVISIBLE
                mIsRecording = false
                mRecordImageButton!!.setImageResource(R.mipmap.btn_video_online)
                mMediaRecorder!!.stop()
                Thread.sleep(100)
                mMediaRecorder!!.reset()
                Thread.sleep(100)
                startPreview()
            } else {
                checkWriteStoragePermission()
            }
        }
        mStillImageButton!!.setOnClickListener {
            checkWriteStoragePermission()
            lockFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (mTextureView.isAvailable) {
            setupCamera(mTextureView.width, mTextureView.height)
            connectCamera()
        } else {
            mTextureView.surfaceTextureListener = mSurfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!permissions.isEmpty()) {
            if (requestCode == REQUEST_CAMERA_PERMISSION_RESULT) {
                if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Ну и иди нахуй тогда", Toast.LENGTH_SHORT).show()
                }
            }
            if (requestCode == REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT) {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Красавчег...", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Чувак подругому ни как, заебал", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val decorView = window.decorView
        if (hasFocus) {
            decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION and
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN and
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY and
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION and
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE and
                    View.SYSTEM_UI_FLAG_FULLSCREEN
        }
    }

    private fun startStillCaptureRequest() {
        try {
            mCaptureRequestBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            mCaptureRequestBuilder!!.addTarget(mImageReader!!.surface)
            mCaptureRequestBuilder!!.set(CaptureRequest.JPEG_ORIENTATION, mTotalRotation)
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
            mPreviewCaptureSession!!.capture(mCaptureRequestBuilder!!.build(), stillCaptureCallback, null)
        } catch (ex: CameraAccessException) {
            ex.printStackTrace()
        }
    }

    private fun setupCamera(width: Int, height: Int) {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue
                }
                val map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val deviceOrientation = windowManager.defaultDisplay.rotation
                mTotalRotation = sensorToDeviceRotation(
                    cameraCharacteristics,
                    deviceOrientation
                )
                val swapRotation = mTotalRotation == 90 || mTotalRotation == 270
                var rotationWidth = width
                var rotationHeight = height
                if (swapRotation) {
                    rotationWidth = height
                    rotationHeight = width
                }
                mPreviewSize =
                        chooseOptimalSize(
                            map.getOutputSizes(
                                SurfaceTexture::class.java
                            ), rotationWidth, rotationHeight
                        )
                mVideoSize =
                        chooseOptimalSize(
                            map.getOutputSizes(
                                MediaRecorder::class.java
                            ), rotationWidth, rotationHeight
                        )
                mImageSize = chooseOptimalSize(
                    map.getOutputSizes(ImageFormat.JPEG), rotationWidth, rotationHeight
                )
                mImageReader = ImageReader.newInstance(mImageSize!!.width, mImageSize!!.height, ImageFormat.JPEG, 1)
                mImageReader!!.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler)
                mCameraId = cameraId
                return
            }
        } catch (ex: CameraAccessException) {
            ex.printStackTrace()
        }
    }

    private fun closeCamera() {
        if (mCameraDevice != null) {
            mCameraDevice!!.close()
            mCameraDevice = null
        }
        if (mMediaRecorder != null) {
            mMediaRecorder!!.release()
            mMediaRecorder = null
        }
    }

    private fun connectCamera() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    cameraManager.openCamera(mCameraId, mCameraDeviceStateCallback, mBackgroundHandler)
                } else {
                    if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                        Toast.makeText(this, "Дай доступ сука", Toast.LENGTH_SHORT).show()
                    }
                    requestPermissions(arrayOf(Manifest.permission.CAMERA),
                        REQUEST_CAMERA_PERMISSION_RESULT
                    )
                }
            } else {
                cameraManager.openCamera(mCameraId, mCameraDeviceStateCallback, mBackgroundHandler)
            }
        } catch (ex: CameraAccessException) {
            ex.printStackTrace()
        }
    }

    private fun startRecord() {
        try {
            setupMediaRecorder()
            val surfaceTexture = textureView.surfaceTexture
            surfaceTexture.setDefaultBufferSize(mPreviewSize!!.width, mPreviewSize!!.height)
            val previewSurface = Surface(surfaceTexture)
            val recordSurface = mMediaRecorder!!.surface
            mCaptureRequestBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            mCaptureRequestBuilder!!.addTarget(previewSurface)
            mCaptureRequestBuilder!!.addTarget(recordSurface)
            mCameraDevice!!.createCaptureSession(
                Arrays.asList(previewSurface, recordSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        try {
                            session.setRepeatingRequest(mCaptureRequestBuilder!!.build(), null, mBackgroundHandler)
                        } catch (ex: CameraAccessException) {
                            ex.printStackTrace()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {

                    }
                }, null
            )
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    private fun startPreview() {
        val surfaceTexture = textureView.surfaceTexture
        surfaceTexture.setDefaultBufferSize(mPreviewSize!!.width, mPreviewSize!!.height)
        val previewSurface = Surface(surfaceTexture)
        try {
            mCaptureRequestBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            mCaptureRequestBuilder!!.addTarget(previewSurface)
            mCameraDevice!!.createCaptureSession(
                Arrays.asList(previewSurface, mImageReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        mPreviewCaptureSession = session
                        try {
                            mPreviewCaptureSession!!.setRepeatingRequest(
                                mCaptureRequestBuilder!!.build(),
                                null,
                                mBackgroundHandler
                            )
                        } catch (ex: CameraAccessException) {
                            ex.printStackTrace()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Toast.makeText(applicationContext, "Ну сорян чо", Toast.LENGTH_SHORT).show()
                    }
                }, null
            )
        } catch (ex: CameraAccessException) {
            ex.printStackTrace()
        }
    }

    private fun startBackgroundThread() {
        mBackgroundHandlerThread = HandlerThread("CameraDemo")
        mBackgroundHandlerThread?.start()
        mBackgroundHandler = Handler(mBackgroundHandlerThread?.looper)
    }

    private fun stopBackgroundThread() {
        mBackgroundHandlerThread?.quitSafely()
        try {
            mBackgroundHandlerThread?.join()
            mBackgroundHandlerThread = null
            mBackgroundHandler = null
        } catch (ex: InterruptedException) {
            ex.printStackTrace()
        }
    }

    private fun checkWriteStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
            ) {
                mIsRecording = true
                mRecordImageButton!!.setImageResource(R.mipmap.btn_video_busy)
                try {
                    createVideoFileName()
                } catch (ex: IOException) {
                    ex.printStackTrace()
                }
                startRecord()
                mMediaRecorder!!.start()
                mChronometer!!.base = SystemClock.elapsedRealtime()
                mChronometer!!.visibility = View.VISIBLE
                mChronometer!!.start()
            } else {
                if (shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    Toast.makeText(this, "Дай доступ, жлоб", Toast.LENGTH_SHORT).show()
                }
                requestPermissions(
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT
                )
            }
        } else {
            mIsRecording = true
            mRecordImageButton!!.setImageResource(R.mipmap.btn_video_busy)
            try {
                createVideoFileName()
            } catch (ex: IOException) {
                ex.printStackTrace()
            }
            startRecord()
            Thread.sleep(100)
            mMediaRecorder!!.start()
            mChronometer!!.base = SystemClock.elapsedRealtime()
            mChronometer!!.visibility = View.VISIBLE
            mChronometer!!.start()
        }
    }

    private fun createVideoFolder() {
        val movieFile = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        mVideoFolder = File(movieFile, "cameraDemoVideoImage")
        if (!mVideoFolder!!.exists()) {
            mVideoFolder!!.mkdirs()
        }
    }

    private fun createVideoFileName(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val prepend = "VIDEO_${timestamp}_"
        val videoFile = File.createTempFile(prepend, ".mp4", mVideoFolder)
        mVideoFileName = videoFile.absolutePath
        return videoFile
    }

    private fun createImageFolder() {
        val imageFile = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        mImageFolder = File(imageFile, "cameraDemoVideoImage")
        if (!mImageFolder!!.exists()) {
            mImageFolder!!.mkdirs()
        }
    }

    private fun createImageFileName(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val prepend = "IMAGE_${timestamp}_"
        val imageFile = File.createTempFile(prepend, ".jpg", mImageFolder)
        mImageFileName = imageFile.absolutePath
        return imageFile
    }

    private fun setupMediaRecorder() {
        mMediaRecorder!!.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mMediaRecorder!!.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mMediaRecorder!!.setOutputFile(mVideoFileName)
        mMediaRecorder!!.setVideoEncodingBitRate(1000000)
        mMediaRecorder!!.setVideoFrameRate(30)
        mMediaRecorder!!.setVideoSize(mVideoSize!!.width, mVideoSize!!.height)
        mMediaRecorder!!.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mMediaRecorder!!.setOrientationHint(mTotalRotation!!)
        mMediaRecorder!!.prepare()
    }

    private fun lockFocus() {
        mCaptureState = STATE_WAIT_LOCK
        mCaptureRequestBuilder!!.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
        try {
            mPreviewCaptureSession!!.capture(
                mCaptureRequestBuilder!!.build(),
                mPreviewCaptureCallback,
                mBackgroundHandler
            )
        } catch (ex: CameraAccessException) {
            ex.printStackTrace()
        }
    }

    companion object {
        private val ORIENTATIONS = SparseIntArray()

        private const val STATE_PREVIEW = 0

        private const val STATE_WAIT_LOCK = 1

        private const val REQUEST_CAMERA_PERMISSION_RESULT = 0

        private const val REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT = 1

        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 0)
            ORIENTATIONS.append(Surface.ROTATION_90, 90)
            ORIENTATIONS.append(Surface.ROTATION_180, 180)
            ORIENTATIONS.append(Surface.ROTATION_270, 270)
        }

        private fun sensorToDeviceRotation(cameraCharacteristics: CameraCharacteristics, deviceOrientation: Int): Int {
            val sensorDeviceOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
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
                fileOutputStream = FileOutputStream(mImageFileName)
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
