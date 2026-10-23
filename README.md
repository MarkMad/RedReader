RedReader
=========

An unofficial, open source Android client for Reddit.

![CI](https://github.com/MarkMad/RedReader/actions/workflows/build.yml/badge.svg?branch=master)
[![Translation status](https://hosted.weblate.org/widgets/redreader/-/svg-badge.svg)](https://hosted.weblate.org/engage/redreader/?utm_source=widget)

[<img src="https://play.google.com/intl/en_us/badges/images/generic/en_badge_web_generic.png"
      alt="Get the upstream RedReader app on Google Play"
      height="80">](https://play.google.com/store/apps/details?id=org.quantumbadger.redreader)
[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get the upstream RedReader app on F-Droid"
     height="80">](https://f-droid.org/packages/org.quantumbadger.redreader/)

Features
--------

* Free and open source, with no ads or tracking
* Lightweight and fast
* Swipe posts left and right to perform customizable actions, such as
    upvote/downvote, or save/hide
* Advanced cache management - automatically stores past versions of posts and
    comments
* Support for multiple accounts
* Two-column tablet mode (can be used on your phone, if it’s big enough)
* Image and comment precaching (optional: always, never, or Wi-Fi only)
* Built-in image viewer, and GIF/video player
* Multiple themes, including night mode, and ultra black for AMOLED displays
* Support for Android 5.0+
* Translations for multiple languages
* Accessibility features and optimization for screen reader use
* Native Text-to-Speech (TTS) engine support to listen to comments read aloud,
    with previous/next comment controls on the comment toolbar and on the
    lock-screen notification
* Audio earcons (short sound cues) between comments while text-to-speech is
    playing, with the option to keep your configured speech rate
* Read-aloud narration strips URL clutter so only readable link labels are spoken


Downloading
-----------

This fork is developed at https://github.com/MarkMad/RedReader and is based on
[QuantumBadger/RedReader](https://github.com/QuantumBadger/RedReader).

It checks stable GitHub releases from `MarkMad/RedReader` once a day while the
app is open. Use **Settings > About > Check for updates** to check manually,
or disable automatic checks in the same screen. Available APKs can be downloaded
and handed to Android's installer after confirmation. Android may first ask you
to allow installations from this app.

Updates must have a newer version code, the same application ID, and the same
signing key as the installed app. Debug installations therefore cannot install
release-signed updates in place. Checks and downloads pause while Tor is enabled.
Install a build containing this updater manually once to enable future updates.

Releases of this fork are published on GitHub:

https://github.com/MarkMad/RedReader/releases

The upstream app is available for free elsewhere:

* Google Play:
    https://play.google.com/store/apps/details?id=org.quantumbadger.redreader
* F-Droid:
    https://f-droid.org/app/org.quantumbadger.redreader


Translating
-----------

Please help us translate RedReader into new languages, or improve the
translations for existing languages!

https://hosted.weblate.org/projects/redreader/strings/

[![Translation status](https://hosted.weblate.org/widgets/redreader/-/287x66-grey.png)](https://hosted.weblate.org/engage/redreader/?utm_source=widget)


Pull Requests
-------------

Please see the contribution guidelines in
 [CONTRIBUTING.md](CONTRIBUTING.md). 


Building
--------

RedReader is built using Gradle. On Linux, simply run:

    ./gradlew installDebug

On Windows, run:

    .\gradlew.bat assembleDebug

Run the quality checks that CI also runs with:

    .\gradlew.bat pmd checkstyle lint test


License
-------

RedReader is licensed under the GPL, version 3. A copy of the license is
included in [LICENSE.txt](LICENSE.txt).


Thanks
------

A full list of contributors is included in the [changelog](src/main/assets/changelog.txt).
