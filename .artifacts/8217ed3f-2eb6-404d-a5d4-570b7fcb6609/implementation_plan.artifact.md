# Implementation Plan - Fix ONNX Model Loading Error

The app is currently failing to load the ONNX model because it's being loaded as a byte array from the `assets` folder. However, the model has an external data file (`nav_velocity_model.onnx.data`), which ONNX Runtime cannot resolve when loading from bytes. To fix this, we must copy both the `.onnx` and `.data` files from assets to the app's internal storage and then load the model using the file path.

## User Review Required

> [!IMPORTANT]
> This change involves copying files from assets to internal storage on every app startup (or if they don't exist). This ensures that the model can be loaded correctly with its external data.

## Proposed Changes

### MainActivity

#### [MODIFY] [MainActivity.kt](file:///C:/Users/cnkha/AndroidStudioProjects/NavAIDR/app/src/main/java/com/isro/navaidr/MainActivity.kt)
- Add a helper function `copyAssetToFile` to copy assets to the internal files directory.
- Update `initOnnxEngine` to copy `nav_velocity_model.onnx` and `nav_velocity_model.onnx.data`.
- Change `ortEnvironment?.createSession(modelBytes)` to `ortEnvironment?.createSession(modelFile.absolutePath)`.

## Verification Plan

### Automated Tests
- N/A (Manual verification on device is required as it involves assets and ONNX Runtime).

### Manual Verification
- Deploy the app to the device.
- Verify that the error message "Model not found in assets" or any other error is gone.
- Confirm that the top bar shows "MODE: AI ENGINE READY".
