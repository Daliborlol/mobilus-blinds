# Blinds

An Android app and a terminal command for Mobilus blinds connected to a COSMO GTW gateway.
Both talk straight to the gateway on your local network. There's no cloud or account, and
nothing stays connected in the background. Away from home you need a VPN into your network.

Unofficial and not affiliated with Mobilus. The protocol comes from the MIT-licensed
[mobilus-client](https://github.com/zpieslak/mobilus-client) project.

## Android app

Install it with [Obtainium](https://github.com/ImranR98/Obtainium) to get updates: choose Add App
and enter this repository's URL. You can also download the APK from the Releases page.

Requires Android 11 or newer. The login screen searches your Wi-Fi network for the gateway and fills
in its address, or you can type it. Then log in with the user you created on the gateway.
Give the gateway a fixed IP in your router, or the app will lose it when the address changes.

Everything below controls one blind. Pick it under "Blind to control" in the app's settings; each
widget can have its own, and you choose the Device Controls you want when you add them.

Ways to control a blind without opening the app:

| Where | How to add it | On the lock screen? |
| --- | --- | --- |
| Notification with buttons | Settings in the app | Yes, on any phone. May need a swipe to expand. |
| Quick Settings tiles | Settings in the app | Yes, from the pulled-down shade |
| Device Controls, one per blind | Device Controls tile | Yes, after turning on "Control from locked device" |
| Widget | Settings in the app, or the launcher's widget list | Only where your phone supports lock screen widgets |
| Launcher shortcuts | Long-press the app icon | No |

Anyone holding your locked phone can use these. Leave them off if that matters to you.

## Terminal

On Arch-based systems, install it from the signed pacman repository. First trust the signing key
and check that its fingerprint is `D44D20B1293F73F552732FE18DB82A66F7D813D4`:

    curl -fsSL https://raw.githubusercontent.com/Daliborlol/mobilus-blinds/main/packaging/signing-key.asc | sudo pacman-key --add -
    sudo pacman-key --lsign-key D44D20B1293F73F552732FE18DB82A66F7D813D4

Then add this to `/etc/pacman.conf`:

    [blinds]
    SigLevel = Required DatabaseRequired
    Server = https://github.com/Daliborlol/mobilus-blinds/releases/latest/download

And install it. Updates then arrive with your normal `pacman -Syu`:

    sudo pacman -Syu blinds

To build it yourself instead, you need JDK 21 and the Android SDK:

    ./gradlew :cli:installDist
    ln -s "$PWD/cli/build/install/blinds/bin/blinds" ~/.local/bin/blinds

Then:

    blinds login 192.168.1.50 admin
    blinds list
    blinds down all
    blinds up kitchen
    blinds set bedroom 40

Needs Java 17 or newer. The login is saved in `~/.config/blinds/config.properties`, readable only
by you.

## Good to know

- Garage doors and switches paired with the gateway are ignored on purpose, because every control
  here works from a locked phone.
- Exact positions are officially supported only on SENSO motors, so the app shows a slider just
  for those.
- Many motors, including COSMO 2WAY, don't report where they are. The position shown is the last
  thing reported or commanded, and it can be wrong after someone uses a wall switch or remote.
- The gateway's protocol isn't fully encrypted: the password hash travels unencrypted during login.
  Only use it on a network you trust.

## Development

    ./gradlew test
    ./gradlew :app:assembleRelease
    tools/fake_gateway.py

Pushing a `v*` tag builds a signed APK and pacman repository and publishes them as a GitHub release.
The workflow needs three repository secrets: `KEYSTORE_BASE64` and `KEYSTORE_PASSWORD`, holding a
PKCS12 keystore with a key called `blinds`, and `PACMAN_GPG_KEY`, holding the armored private key
that matches `packaging/signing-key.asc`.

The fake gateway stands in for the hardware. Log in as `admin` / `secret`, using `127.0.0.1` from
this machine or `10.0.2.2` from an emulator (add `--bind 0.0.0.0` for a real phone). It's written
from the client's source, so passing against it doesn't prove a real gateway agrees.
