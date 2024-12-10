package com.example.signlanguage;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Size;
import android.view.SurfaceView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_CAMERA = 100;
    private Interpreter tflite;
    private TextView predictionTextView;
    private ExecutorService executorService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        predictionTextView = findViewById(R.id.prediction_text);
        SurfaceView cameraPreview = findViewById(R.id.camera_preview);

        executorService = Executors.newSingleThreadExecutor();

        // Request camera permission if not granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CODE_CAMERA);
        } else {
            startCamera(cameraPreview);
        }

        // Load TensorFlow Lite model
        loadModel();
    }

    private void startCamera(SurfaceView cameraPreview) {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // Create a CameraSelector for the back camera
                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                // Configure ImageAnalysis use case
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(224, 224)) // Match model input size
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(executorService, image -> {
                    Bitmap bitmap = imageProxyToBitmap(image);
                    if (bitmap != null) {
                        String prediction = classifyFrame(bitmap);
                        runOnUiThread(() -> predictionTextView.setText("Prediction: " + prediction));
                    }
                    image.close();
                });

                // Bind the camera to the lifecycle
                Camera camera = cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private Bitmap imageProxyToBitmap(ImageProxy image) {
        // Placeholder: Replace with the actual implementation to convert ImageProxy to Bitmap
        return null;
    }

    private void loadModel() {
        try {
            FileInputStream fileInputStream = new FileInputStream(getAssets().openFd("model.tflite").getFileDescriptor());
            FileChannel fileChannel = fileInputStream.getChannel();
            MappedByteBuffer mappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size());
            tflite = new Interpreter(mappedByteBuffer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String classifyFrame(Bitmap bitmap) {
        ByteBuffer inputBuffer = preprocessBitmap(bitmap);

        // Run inference
        float[][] result = new float[1][10]; // Adjust size based on model output
        tflite.run(inputBuffer, result);

        // Interpret the results
        return interpretResult(result);
    }

    private ByteBuffer preprocessBitmap(Bitmap bitmap) {
        int inputSize = 224; // Adjust based on model input size
        Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true);
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3);
        byteBuffer.order(ByteOrder.nativeOrder());
        int[] intValues = new int[inputSize * inputSize];
        resizedBitmap.getPixels(intValues, 0, resizedBitmap.getWidth(), 0, 0, resizedBitmap.getWidth(), resizedBitmap.getHeight());

        int pixel = 0;
        for (int i = 0; i < inputSize; ++i) {
            for (int j = 0; j < inputSize; ++j) {
                int val = intValues[pixel++];
                byteBuffer.putFloat(((val >> 16) & 0xFF) / 255.0f);
                byteBuffer.putFloat(((val >> 8) & 0xFF) / 255.0f);
                byteBuffer.putFloat((val & 0xFF) / 255.0f);
            }
        }
        return byteBuffer;
    }

    private String interpretResult(float[][] result) {
        int maxIndex = 0;
        float maxProb = result[0][0];
        for (int i = 1; i < result[0].length; i++) {
            if (result[0][i] > maxProb) {
                maxProb = result[0][i];
                maxIndex = i;
            }
        }
        return "Sign " + maxIndex; // Adjust as per your labels
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tflite != null) {
            tflite.close();
        }
        executorService.shutdown();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_CAMERA && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            SurfaceView cameraPreview = findViewById(R.id.camera_preview);
            startCamera(cameraPreview);
        }
    }
}
