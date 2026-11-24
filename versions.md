# v0.2.4
- Remove the Bluetooth remote added in v0.2.3; it disconnected right after pairing. The app no longer asks for Bluetooth permissions

# v0.2.3
- LG webOS: search YouTube from the phone. YouTube on LG TVs ignores typed text, so when YouTube is on screen the keyboard becomes "Search YouTube" and opens YouTube's results for what you type
- Bluetooth remote: the phone can act as a Bluetooth keyboard and media remote for when Wi-Fi isn't available (pair once from the TV's Bluetooth menu)

# v0.2.2
- Google TV / Android TV typing: use the text box details the TV sends the same way as the reference implementation, so typed text should now reach the TV
- Keyboard sheet on Google TV: a "Type with key presses" switch as a fallback, and a short log of what the TV reports about its text boxes

# v0.2.1
- Fix typing on Google TV / Android TV: letters now appear in the TV's text box as you type

# v0.2.0
- Google TV / Android TV support: find them on Wi-Fi, pair by typing the code shown on the TV, then use arrows, OK, Back, Home, Settings, Info, colour keys, numbers, volume, mute, channels, media keys, power, typing, Input key and a list of common apps
- The app tells webOS and Google TVs apart by itself; for a typed IP it checks which ports answer, and asks you to choose if the TV doesn't reply
- LG webOS features are unchanged

# v0.1.0
- First SmartTVControl release, based on webOSControl v0.1.9 (LG webOS TVs)
- Find TVs on Wi-Fi or by IP, pair once and remember the TV
- Power off, and power on with Wake-on-LAN
- Volume, mute (phone volume keys work too), channels, arrow keys with OK, Back, Home, Settings, Info, color keys and number pad
- Touchpad mode for the TV pointer
- Media keys, app launcher, input switching, typing text on the TV (including YouTube search)
- Checks GitHub Releases for updates and offers Update or Ignore
