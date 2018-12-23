package com.example.anvar.camerademo.myCamera

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.CoroutineContext
import kotlin.math.sign

interface GetCamera {

    fun sendAction(action: UiAction)
}

class GetCameraImpl(textureView: AutoFitTextureView) : GetCamera, CoroutineScope {

    init {
        process(textureView)
    }

    private var job = Job()

    private val uiActionChannel = Channel<UiAction>()

    private val cameraActionChannel = Channel<CameraAction>()

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    override fun sendAction(action: UiAction) = runBlocking {
        uiActionChannel.send(action)
    }

    private fun process(textureView: AutoFitTextureView) {
        launch {
            val state = CameraState(textureView)

            job.invokeOnCompletion {
                uiActionChannel.cancel()
                cameraActionChannel.cancel()
            }

            while (true) {
                select<Unit> {
                    uiActionChannel.onReceive { action ->
                        when (action) {
                            UiAction.START -> {
                                createImageFolder(state, "CameraImageFolder")
                                startBackgroundThread(state)
                            }
                            UiAction.RESUME -> {
                                resume(state)
                            }
                            UiAction.PAUSE -> {
                                pause(state)
                            }
                            UiAction.STOP -> {
                                stopBackgroundThread(state)
                            }
                            UiAction.DESTROY -> {
                                job.cancel()
                            }
                            UiAction.TAKE_PHOTO -> {
                                lockFocus(state)
                            }
                            UiAction.SWITCH_CAMERA -> {
                                reverseCamera(state)
                            }
                            UiAction.SWITCH_FLASH -> {
                                switchFlash(state)
                            }
                        }
                    }
                    cameraActionChannel.onReceive { action ->
                        when (action) {
                            is CameraAction.TextureViewAvailable -> {
                                startCamera(state)
                            }
                            is CameraAction.CameraCaptureSessionOnConfigured -> {
                                state.previewCaptureSession = action.session
                                try {
                                    state.previewCaptureSession!!.setRepeatingRequest(
                                        state.captureRequestBuilder!!.build(),
                                        null,
                                        state.handler
                                    )
                                } catch (ex: CameraAccessException) {
                                    ex.printStackTrace()
                                }
                            }
                            is CameraAction.CameraCaptureSessionOnCaptureStarted -> {
                                try {
                                    createImageFileName(state)
                                } catch (ex: IOException) {
                                    ex.printStackTrace()
                                }
                            }
                            is CameraAction.CameraCaptureSessionOnCaptureCompleted -> {
                                startStillCaptureRequest(state)
                                Toast.makeText(state.textureView.context, "Хопа", Toast.LENGTH_SHORT).show()
                            }
                            is CameraAction.CameraDeviceOnOpened -> {
                                state.cameraDevice = action.camera
                                startPreview(state)
                            }
                            is CameraAction.CameraDeviceOnDisconnected -> {
                                action.camera.close()
                                state.cameraDevice = null
                            }
                            is CameraAction.CameraDeviceOnError -> {
                                action.camera.close()
                                state.cameraDevice = null
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startCamera(state: CameraState) {
        val cameraId = if (state.cameraRotationState == CameraType.FRONT) {
            setUpCameraId(state, CameraType.FRONT)
        } else {
            setUpCameraId(state)
        }
        connectCamera(state, cameraId)
    }

    private fun restartCamera(state: CameraState) {
        disconnectCamera(state)
        startCamera(state)
    }

    private fun lockFocus(state: CameraState) {
        val previewCaptureCallback: CameraCaptureSession.CaptureCallback =
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) = runBlocking {
                    super.onCaptureCompleted(session, request, result)
                    cameraActionChannel.send(CameraAction.CameraCaptureSessionOnCaptureCompleted)
                }
            }
        try {
            state.captureRequestBuilder!!.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CaptureRequest.CONTROL_AF_TRIGGER_START
            )
            state.previewCaptureSession!!.capture(
                state.captureRequestBuilder!!.build(),
                previewCaptureCallback,
                state.handler
            )
        } catch (ex: CameraAccessException) {
            ex.printStackTrace()
        }
    }

    private fun reverseCamera(state: CameraState) {
        state.cameraRotationState = when (state.cameraRotationState) {
            CameraType.BACK -> CameraType.FRONT
            CameraType.FRONT -> CameraType.BACK
        }
        restartCamera(state)
    }

    private fun switchFlash(state: CameraState) {
        state.isTorchOn = if (state.isTorchOn) {
//            state.flashSwitchButton.setImageResource(R.mipmap.ic_flash_off_white_24dp)
            print("set image")
            false
        } else {
//            state.flashSwitchButton.setImageResource(R.mipmap.ic_flash_auto_white_24dp)
            print("set image")
            true
        }
        restartCamera(state)
    }

    @SuppressLint("MissingPermission")
    private fun connectCamera(state: CameraState, cameraId: String) {
        val cameraDeviceStateCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) = runBlocking {
                cameraActionChannel.send(CameraAction.CameraDeviceOnOpened(camera))
            }

            override fun onDisconnected(camera: CameraDevice) = runBlocking {
                cameraActionChannel.send(CameraAction.CameraDeviceOnDisconnected(camera))
            }

            override fun onError(camera: CameraDevice, error: Int) = runBlocking {
                cameraActionChannel.send(CameraAction.CameraDeviceOnError(camera))
            }
        }
        try {
            (state.textureView.context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).openCamera(
                cameraId,
                cameraDeviceStateCallback,
                state.handler
            )
        } catch (ex: CameraAccessException) {
            ex.printStackTrace()
        }
    }

    private fun disconnectCamera(state: CameraState) {
        pause(state)
        stopBackgroundThread(state)
    }

    private fun startPreview(state: CameraState) {
        val surfaceTexture = state.textureView.surfaceTexture
        surfaceTexture.setDefaultBufferSize(state.previewSize!!.width, state.previewSize!!.height)
        val previewSurface = Surface(surfaceTexture)
        val captureStateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) = runBlocking {
                cameraActionChannel.send(CameraAction.CameraCaptureSessionOnConfigured(session))
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Toast.makeText(state.textureView.context, "Ну сорян чо", Toast.LENGTH_SHORT).show()
            }
        }
        try {
            state.captureRequestBuilder = state.cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            state.captureRequestBuilder!!.addTarget(previewSurface)
            setAutoFlash(state)
            state.cameraDevice!!.createCaptureSession(
                Arrays.asList(previewSurface, state.imageReader!!.surface),
                captureStateCallback
                , null
            )
        } catch (ex: CameraAccessException) {
            ex.printStackTrace()
        }
    }

    private fun setAutoFlash(state: CameraState) {
        try {
            if (state.cameraRotationState == CameraType.BACK) {
                if (state.isFlashSupported!!) {
                    if (state.isTorchOn) {
                        state.captureRequestBuilder!!.set(
                            CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                        )
                    } else if (!state.firstStart) {
                        state.captureRequestBuilder!!.set(
                            CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_OFF
                        )
                        state.captureRequestBuilder!!.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    }
                    state.firstStart = false
                }
            }
        } catch (ex: CameraAccessException) {
            ex.printStackTrace()
        }
    }

    private fun startStillCaptureRequest(state: CameraState) {
        try {
            state.captureRequestBuilder = state.cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            state.captureRequestBuilder!!.addTarget(state.imageReader!!.surface)
            state.captureRequestBuilder!!.set(
                CaptureRequest.CONTROL_AF_MODE,
                state.captureRequestBuilder!!.get(CaptureRequest.CONTROL_AF_MODE)
            )
            state.captureRequestBuilder!!.set(CaptureRequest.JPEG_ORIENTATION, state.totalRotation)
            val stillCaptureCallback: CameraCaptureSession.CaptureCallback =
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureStarted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        timestamp: Long,
                        frameNumber: Long
                    ) = runBlocking {
                        super.onCaptureStarted(session, request, timestamp, frameNumber)
                        cameraActionChannel.send(CameraAction.CameraCaptureSessionOnCaptureStarted)
                    }
                }
            state.previewCaptureSession!!.capture(state.captureRequestBuilder!!.build(), stillCaptureCallback, null)
        } catch (ex: CameraAccessException) {
            ex.printStackTrace()
        }
    }

    private fun startBackgroundThread(state: CameraState) {
        val handlerThread = HandlerThread("GetCamera")
        handlerThread.start()
        val handler = Handler(handlerThread.looper)

        state.handler = handler
        state.handlerThread = handlerThread
    }

    private fun stopBackgroundThread(state: CameraState) {
        state.handlerThread?.quitSafely()
        try {
            state.handlerThread?.join()
            state.handlerThread = null
            state.handler = null
        } catch (ex: InterruptedException) {
            ex.printStackTrace()
        }
    }

    private fun resume(state: CameraState) = runBlocking {
        if (state.textureView.isAvailable) {
            cameraActionChannel.send(CameraAction.TextureViewAvailable)
        } else {
            state.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) =
                    runBlocking {
                        cameraActionChannel.send(CameraAction.TextureViewAvailable)
                    }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?): Boolean {
                    return false
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture?, width: Int, height: Int) {
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) {
                }
            }
        }
    }

    private fun pause(state: CameraState) {
        state.cameraDevice?.close()
        state.cameraDevice = null
    }

    private fun setUpCameraId(state: CameraState, cameraType: CameraType = CameraType.BACK): String {
        val onImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
            state.handler!!.post(ImageSaver(reader.acquireLatestImage(), state.imageFileName!!))
        }
        val manager = state.textureView.context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
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
                state.isFlashSupported = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
            }
            val imageSize = setPreviewSizes(characteristics, state)
            val imageReader = ImageReader.newInstance(imageSize.width, imageSize.height, ImageFormat.JPEG, 1)
            imageReader.setOnImageAvailableListener(onImageAvailableListener, state.handler)
            state.imageReader = imageReader
            return cameraId
        }
        throw IllegalStateException("Could not set Camera Id")
    }

    private fun setPreviewSizes(characteristics: CameraCharacteristics, state: CameraState): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val context = state.textureView.context as Activity
        val deviceOrientation = context.windowManager.defaultDisplay.rotation
        val totalRotation = sensorToDeviceRotation(
            characteristics,
            deviceOrientation,
            state.cameraRotationState
        )
        state.totalRotation = totalRotation
        val swapRotation = totalRotation == 90 || totalRotation == 270
        var rotationWidth = state.textureView.width
        var rotationHeight = state.textureView.height
        if (swapRotation) {
            rotationWidth = state.textureView.height
            rotationHeight = state.textureView.width
        }
        val previewSize = chooseOptimalSize(
            map!!.getOutputSizes(
                SurfaceTexture::class.java
            ), rotationWidth, rotationHeight
        )
        state.previewSize = previewSize
        state.textureView.setAspectRatio(previewSize.height, previewSize.width)
        return chooseOptimalSize(
            map.getOutputSizes(ImageFormat.JPEG), rotationWidth, rotationHeight
        )
    }

    private fun sensorToDeviceRotation(
        cameraCharacteristics: CameraCharacteristics,
        deviceOrientation: Int,
        cameraType: CameraType
    ): Int {
        val sensorDeviceOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
        val orientations = setOrientations(cameraType)
        val newDeviceOrientation = orientations.get(deviceOrientation)
        return (sensorDeviceOrientation!! + newDeviceOrientation) % 360
    }

    private fun setOrientations(cameraType: CameraType): SparseIntArray {
        val orientations = SparseIntArray()
        when (cameraType) {
            CameraType.BACK -> {
                orientations.append(Surface.ROTATION_0, 90)
                orientations.append(Surface.ROTATION_90, 180)
                orientations.append(Surface.ROTATION_180, 270)
                orientations.append(Surface.ROTATION_270, 0)
            }
            CameraType.FRONT -> {
                orientations.append(Surface.ROTATION_0, 90)
                orientations.append(Surface.ROTATION_90, 0)
                orientations.append(Surface.ROTATION_180, 270)
                orientations.append(Surface.ROTATION_270, 180)
            }
        }
        return orientations
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

    private fun createImageFolder(state: CameraState, imageFolderName: String) {
        val imageFile = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val imageFolder = File(imageFile, imageFolderName)
        state.imageFolder = imageFolder
        if (!imageFolder.exists()) {
            imageFolder.mkdirs()
        }
    }

    @SuppressLint("SimpleDateFormat")
    private fun createImageFileName(state: CameraState): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val prepend = "IMAGE_${timestamp}_"
        val imageFile = File.createTempFile(prepend, ".jpg", state.imageFolder)
        state.imageFileName = imageFile.absolutePath
        return imageFile
    }
}

data class CameraState(
    val textureView: AutoFitTextureView,
    var isTorchOn: Boolean = false,
    var firstStart: Boolean = true,
    var cameraRotationState: CameraType = CameraType.BACK,
    var captureRequestBuilder: CaptureRequest.Builder? = null,
    var previewCaptureSession: CameraCaptureSession? = null,
    var handlerThread: HandlerThread? = null,
    var cameraDevice: CameraDevice? = null,
    var isFlashSupported: Boolean? = null,
    var imageReader: ImageReader? = null,
    var imageFileName: String? = null,
    var totalRotation: Int? = null,
    var previewSize: Size? = null,
    var imageFolder: File? = null,
    var handler: Handler? = null
)

sealed class CameraAction {
    object TextureViewAvailable : CameraAction()
    object CameraCaptureSessionOnCaptureCompleted : CameraAction()
    object CameraCaptureSessionOnCaptureStarted : CameraAction()
    data class CameraCaptureSessionOnConfigured(val session: CameraCaptureSession) : CameraAction()
    data class CameraDeviceOnOpened(val camera: CameraDevice) : CameraAction()
    data class CameraDeviceOnDisconnected(val camera: CameraDevice) : CameraAction()
    data class CameraDeviceOnError(val camera: CameraDevice) : CameraAction()
}

enum class CameraType {
    FRONT,
    BACK
}

enum class UiAction {
    START, RESUME, PAUSE, STOP, DESTROY, TAKE_PHOTO, SWITCH_CAMERA, SWITCH_FLASH
}

object CompareSizeByArea : Comparator<Size> {
    override fun compare(o1: Size?, o2: Size?): Int {
        return sign(o1!!.width.toDouble() * o1.height.toDouble() / o2!!.width.toDouble() * o2.height.toDouble()).toInt()
    }
}

class ImageSaver(
    private val mImage: Image,
    private val imageFileName: String
) : Runnable {
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