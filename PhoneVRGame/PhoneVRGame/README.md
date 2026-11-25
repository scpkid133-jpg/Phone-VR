# PhoneVR (Mi 11 Lite 5G)

A starter Android passthrough/hand-tracking project:
- rear camera preview
- grey-tinted camera view
- MediaPipe hand landmarks for up to two hands
- cyan hand skeleton and yellow index fingertip
- point with index extended and middle/ring/pinky folded at the red YouTube icon to open YouTube search

## Build

1. Install Android Studio on your PC.
2. Open this `PhoneVRGame` folder in Android Studio and let Gradle sync.
3. Download the official MediaPipe Hand Landmarker model (`hand_landmarker.task`) from the MediaPipe Hand Landmarker documentation:
   https://developers.google.com/mediapipe/solutions/vision/hand_landmarker
4. Create `app/src/main/assets/` and place `hand_landmarker.task` inside it.
5. Connect the Xiaomi Mi 11 Lite 5G with USB debugging enabled, or use Android Studio's APK build menu.
6. Build > Build APK(s). Install the generated APK and allow camera permission.

## Notes

- This is starter code, not a finished room-scale VR system. It does not estimate full body position or safe walking space.
- Keep the camera view clear and test while seated/stationary first. The camera view has limited field of view and may have delay; do not walk around relying on it as your only view of the room.
- The YouTube action opens YouTube search in the YouTube app (or browser fallback). It does not embed YouTube playback or provide finger-based selection inside YouTube itself yet.
- Camera and hand processing are local on the phone. No account or API key is required.
- The simple pointing gesture uses screen-up finger geometry and works best when your hand is upright in the camera image. Lighting and camera angle affect tracking.
