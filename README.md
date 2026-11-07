# DG4Sat: Look4Sat基于DG-LAB的自动提醒功能
在平常打卫星的时候, 我们可能会因为其它的事情忘记转动天线使其对准卫星, 因此我(BA4IHR)使用DeepSeek V4.1 Flash对原版的Look4Sat进行了改进, 当我们的准心和卫星的偏离程度较大时, Look4Sat会通过蓝牙启动DG-LAB的电击以提醒我们关注天线位置
- [x] 设置启动阈值
- [x] 设置强度范围
- [x] 输出通道选择

鸣谢以下项目:[Look4Sat](https://github.com/rt-bishop/Look4Sat)&[dglab-bluetooth-protocol](https://github.com/dungeonlab-open/dglab-bluetooth-protocol)

<img width="250" alt="Screenshot_2026-11-07-22-25-16-720_top bravocn dg4sat" src="https://github.com/user-attachments/assets/9b60555c-6c17-4018-9649-c35122410cab" />
<img width="250" alt="Screenshot_2026-11-07-22-25-22-611_top bravocn dg4sat" src="https://github.com/user-attachments/assets/e1ec41ab-3d2e-43e6-aad2-f8a2812cea1d" />
<img width="250" alt="Screenshot_2026-11-07-22-25-25-113_top bravocn dg4sat" src="https://github.com/user-attachments/assets/1754d61f-b306-462b-8fce-bb62465235a4" />


# Look4Sat: Satellite tracker

[![Look4Sat CI](https://github.com/rt-bishop/Look4Sat/actions/workflows/release.yml/badge.svg)](https://github.com/rt-bishop/Look4Sat/actions/workflows/release.yml)

[<img src="https://play.google.com/intl/en_gb/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="80">](https://play.google.com/store/apps/details?id=com.rtbishop.look4sat)
[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="80">](https://f-droid.org/packages/com.rtbishop.look4sat/)

### Radio satellite tracker and pass predictor for Android, inspired by Gpredict

<p float="left">
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="192"/>
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="192"/>
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="192"/>
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="192">
</p>

### Track satellite passes with ease!

Thanks to [Celestrak](https://celestrak.com/) and [SatNOGS](https://satnogs.org/) you have access to over 9000 active satellites.\
You can search the entire database by NORAD Catalog Number or the satellite's name.

Orbital positions and passes are calculated relative to your location.\
To get reliable data make sure to set the station position via the app Settings.

The application is built using Kotlin, Coroutines, Jetpack Compose and Navigation.\
It is now and always will be completely ad-free and open-source.

## Main features:

*  Predicting satellite positions and passes for up to 10 days
*  Showing the list of currently active and upcoming satellite passes
*  Showing the active pass progress, polar trajectory and transceivers info
*  Showing the satellite positional data, footprint and ground track on the map
*  Custom TLE satellite data import is available via Three Line Element .txt files
*  Offline first: calculations are made offline. Weekly TLE data update is recommended.

## Associated projects:

- [Look4Sat-rPi](https://github.com/kampji/Look4Sat-rPi) - An independent Raspberry Pi OS port of Look4Sat,
bringing satellite tracking to touchscreen devices.

## Star History

<a href="https://star-history.dera.page/#rt-bishop/Look4Sat&type=timeline&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://star-history.dera.page/svg?repos=rt-bishop/Look4Sat&type=timeline&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://star-history.dera.page/svg?repos=rt-bishop/Look4Sat&type=timeline&legend=top-left" />
   <img alt="Star History Chart" src="https://star-history.dera.page/svg?repos=rt-bishop/Look4Sat&type=timeline&legend=top-left" />
 </picture>
</a>
