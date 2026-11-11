# Airpoint experimental eye control

This project includes an opt-in experimental gaze-control mode built on MediaPipe Face Landmarker.

## Open the hidden experimental lab

1. Install and open Airpoint.
2. Tap the **AIRPOINT** heading seven times quickly.
3. In **EXPERIMENTAL LAB**, enable **Eye control (beta)**.
4. Allow camera access and enable Airpoint in Android Accessibility settings.
5. Choose **Start nine-point calibration**. Hold the phone steady at roughly 30–40 cm in good, even lighting and follow each target with your eyes while keeping your head as still as possible.
6. After calibration, gaze moves the cursor. Dwell-click and the existing facial gestures remain available.

Tap the AIRPOINT heading seven times again to hide the lab. The eye-control preference remains saved until switched off.

## Build with GitHub Actions

Push the complete project to GitHub and run the **Build Airpoint APK** workflow. The workflow downloads the official MediaPipe Gesture Recognizer and Face Landmarker task assets into `app/src/main/assets/`, then runs `gradle assembleDebug --no-daemon` and uploads the APK as the `airpoint-apk` artifact.

## Notes

- Gaze estimation and calibration run locally on the device; no camera frames are uploaded by this app.
- Eye tracking is experimental. Accuracy varies with lighting, camera placement, glasses, eye shape, and head movement. It is not a substitute for Android's built-in accessibility features.
- Camera use is visible through Android's camera indicator and the foreground-service notification. The user must explicitly enable camera permission and Accessibility.
