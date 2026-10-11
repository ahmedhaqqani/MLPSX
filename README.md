# Gold ORB

Android app that tracks the **opening range breakout (ORB) of gold** for the Asian, London
and New York sessions, each in its own box with a coloured signal dot.

## How a session box works

1. **Opening range (OR):** high and low of the first 30 minutes after the session opens.
2. **Volume profile:** fixed-range profile over the same 30 minutes → POC, VAH and VAL
   (70% value area, 24 rows; both adjustable).
3. **Every completed 5-minute close after the range is classified:**

| Dot | Condition |
| --- | --- |
| 🟢 green | close above OR high (and above VAH) |
| light green | inside the range, above VAH |
| 🟠 orange | inside the value area (VAL – VAH) |
| light red | inside the range, below VAL |
| 🔴 red | close below OR low (and below VAL) |
| 🟣 purple – **FAILED** | broke out, then a 5m candle closed back inside the range |

After a FAILED, a new close outside the range is a **RE-BREAK** (green or red again).
Each box counts the 5-minute candles that closed above, inside and below the range.

The mini chart shows the OR high (green dashed line), OR low (red dashed line) and the
value area (blue band). Faint yellow vertical lines mark the Shanghai and London opens on
the Asian chart, and the New York open on the London chart.

- A box shows only the latest occurrence of its session. It stops updating when that
  session closes and resets when the session opens again. For example, when the Asian session
  opens, London and New York keep showing the previous day until their own opens.
- Weekends are skipped.

## Settings

- **TradingView:** the MCP server URL and gold symbol (`OANDA:XAUUSD` spot or `COMEX:GC1!`
  futures). **Connect TradingView** runs the OAuth sign-in in the browser (PKCE with
  automatic client registration). Under **Advanced** you can enter a client ID or paste
  an access token instead.
- **Session times:** your time zone is detected automatically and all times are shown in
  your local time. Defaults are Tokyo 09:00–18:00, London 08:00–17:00 and New York
  09:30–17:00, each following its own city's daylight saving. Tap a time to change it.
- **Notifications:** opening range set, and breakout / FAILED / re-break.
- **Background watching:** a foreground service refreshes just after every 5-minute close
  while a session is open. It sleeps between sessions and over weekends.

## Project layout

- `core/`: pure Kotlin. Session schedule, volume profile, ORB engine, the MCP client
  (Streamable HTTP) and OAuth. It has unit tests and needs no Android SDK:
  `./gradlew :core:test`.
- `app/`: Android app (Jetpack Compose, minSdk 26). It is built when an Android SDK is
  present (`ANDROID_HOME` or `local.properties`).

## Getting the APK

Every push runs the **Build APK** GitHub Actions workflow. Download `GoldORB-debug-apk`
from the run's artifacts, unzip it and install `app-debug.apk` on your phone (allow
"install unknown apps"). To build it yourself, open the project in Android Studio or run
`./gradlew :app:assembleDebug`.
