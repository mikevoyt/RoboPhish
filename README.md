RoboPhish
=========

A Open Source Phish music player, utilizing the Phish.in API for streaming audio.  Forked from [Android Universal Music Player](https://github.com/googlesamples/android-UniversalMusicPlayer).

Pre-requisites
--------------

- Android SDK v17
- JDK 17 (for Android Gradle Plugin 8.x)

Run In Android Studio
---------------------

1) Open `/Users/mikevoyt/Development/Rokk/RoboPhish` in Android Studio.
2) Set Gradle JDK to 17:
   Android Studio > Settings/Preferences > Build, Execution, Deployment > Gradle > Gradle JDK.
3) Sync Gradle when prompted.
4) Select a run configuration for the `mobile` module.
5) Start an emulator (Device Manager) or connect a USB device.
6) Run the app (Run ▶ or Cmd+R/Ctrl+R).

Command Line Build/Run
----------------------

Build:
- `./gradlew :mobile:assembleDebug`

Install (with a device/emulator connected):
- `./gradlew :mobile:installDebug`

Launch:
- `adb shell am start -n com.bayapps.android.robophish/.ui.MusicPlayerActivity`

Troubleshooting
---------------

- Gradle requires JDK 17. If you see Java 11 errors, set `org.gradle.java.home` to a JDK 17 install
  or pick JDK 17 in Android Studio Gradle settings.
- If `adb devices` is empty, start an emulator or connect a USB device with debugging enabled.
- If the emulator fails to start, use Android Studio's Device Manager to create/launch an AVD.

Contributors
------------

We're looking for contributors to this project.  If you are interested in contributing, please take a look at the Issues list and pick an issue you'd like to tackle, or file a new one, and assign it to yourself.

Moving forward, the Master branch is for releases, and the Develop branch is used for, well, development.  Please work from the Develop branch, and file pull requests for your contribution.  Those pull requests will be merged into Develop branch, and then eventually merged into Master for the next release to the Play Store.


License
-------

Copyright 2014 The Android Open Source Project, Inc.

Licensed to the Apache Software Foundation (ASF) under one or more contributor
license agreements.  See the NOTICE file distributed with this work for
additional information regarding copyright ownership.  The ASF licenses this
file to you under the Apache License, Version 2.0 (the "License"); you may not
use this file except in compliance with the License.  You may obtain a copy of
the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
License for the specific language governing permissions and limitations under
the License.
