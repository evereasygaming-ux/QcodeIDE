---
name: qcode-video
description: >-
  Create a narrated video from a plain-language prompt in Hindi, English, or Odia.
  Use when the user asks for a video, reel, short, story, tutorial, explainer,
  advertisement, or kids' story with images and voiceover. The agent plans scenes,
  writes image prompts and narration, then this skill generates images, speech,
  and an MP4.
---

# QCODE Video Studio

Turn a user's idea into a finished video on the device:

1. Understand the prompt and detect the language: **Hindi, English, or Odia only**.
2. Break it into 3–8 scenes.
3. Write a short image prompt for each scene **in English** (image models follow English best).
4. Write the narration for each scene **in the user's language**.
5. Save a JSON spec, run the generator, report the MP4 path.

Do not ask for confirmation when the request already contains enough detail.
Choose a sensible language, tone, voice, and scene count automatically.

## Command

```sh
cd /workspace/.qcode-tools/video
node qcode-video.mjs --spec /workspace/.qcode-tools/video/my-video.json \
  --out /workspace/qcodesmoke/qcode-videos/my-video.mp4
```

Output defaults to `qcode-videos/` under the current project. Always pass `--out`
with a clear filename. The finished file is a standard H.264/AAC MP4 (720×1280
vertical by default).

Choose the voice engine with `--tts-provider gemini|elevenlabs|edge|auto`
(default `auto`: Gemini → ElevenLabs → Edge). For Odia, Edge has no voice, so
use Gemini or ElevenLabs.

## CoreX Flow (recommended)

Use Google Flow through **CoreX** for the visuals, then this tool adds voice and
renders the MP4 on device. Flow uses the signed-in QCODE Browser session and its
own credits.

Before a Flow run, start the browser and expose its DevTools socket to the guest.
On the host, run the helper (pass the current adb serial if needed):

```sh
~/deepx-cdp-forward.sh 10.205.161.73:33393
```

Equivalent manual commands:

```sh
adb shell am start -n dev.qcode.ide.debug/dev.blamspot.jcode.MainActivity
adb forward tcp:9222 localabstract:webview_devtools_remote_$(adb shell pidof dev.qcode.ide.debug | tr -d '\r')
```

Open `https://flow.google.com` once in the QCODE Browser and sign in. Then add
these fields to the spec:

```json
"imageProvider": "flow",
"flowProject": "https://flow.google.com/project/<project-id>"
```

`flowProject` is optional; when omitted, the tool creates a new Flow project.
The first run may create the project and take longer. Flow generates 16:9 images
(1376×768); for a vertical 720×1280 reel, keep `"fit": "cover"` and compose with
the crop, or set `"width": 1280, "height": 720` for landscape.

If Flow fails (not signed in, CDP forward missing, reCAPTCHA, quota), the tool
falls back to Pollinations automatically. Do not stop after a Flow failure.

## Spec

```json
{
  "title": "The Little Star",
  "language": "hi",
  "ttsProvider": "auto",
  "voice": "hi-IN-SwaraNeural",
  "geminiVoice": "Kore",
  "elevenLabsVoice": "<voice-id>",
  "imageProvider": "flow",
  "flowProject": "https://flow.google.com/project/<project-id>",
  "width": 720,
  "height": 1280,
  "fps": 30,
  "rate": "+3%",
  "scenes": [
    {
      "imagePrompt": "children's storybook illustration, a tiny glowing star above a village, warm night, no text",
      "narration": "एक छोटे से गाँव में, आसमान में एक प्यारा सा तारा रहता था।"
    }
  ]
}
```

Scene fields:

- `imagePrompt` — English description for image generation.
- `image` — optional local image path; use this instead of `imagePrompt` for screenshots or supplied art.
- `narration` — spoken text in the target language.
- `fit` — `cover` (default) or `contain` for screenshots/UI captures.
- `seconds` — optional fixed duration; omit to let the voiceover set the length.
- `rate`, `pitch`, `volume` — optional per-scene voice controls.

Global fields: `language`, `ttsProvider`, `voice`, `geminiVoice`,
`elevenLabsVoice`, `width`, `height`, `fps`, `fit`, `rate`.

## Languages and voices

Only **Hindi (`hi`)**, **English (`en`)**, and **Odia (`or`)** are supported.
Natural voices only — do not use espeak or robotic fallbacks.

| Language | Edge voice | Notes |
| --- | --- | --- |
| Hindi | `hi-IN-SwaraNeural` / `hi-IN-MadhurNeural` | Edge is natural and free |
| English (India) | `en-IN-NeerjaNeural` / `en-IN-PrabhatNeural` | Edge is natural and free |
| English (US) | `en-US-AriaNeural` / `en-US-GuyNeural` | Edge is natural and free |
| Odia | — | Edge has no Odia; use Gemini or ElevenLabs |

### TTS providers

| Provider | Free tier | Hindi | English | Odia |
| --- | --- | --- | --- | --- |
| `gemini` (Google AI Studio) | 10 requests/day (Nov 2026) | yes | yes | yes |
| `elevenlabs` | 10,000 credits/month | yes | yes | yes (`eleven_v3`) |
| `edge` | free | yes | yes | no |

`auto` order is **Gemini → ElevenLabs → Edge**. For Odia, Gemini is the
recommended free option. Do not claim a video is done if all providers failed.

Set keys in `/workspace/.qcode-tools/video/.env` (copy `tts.env.example`):

```sh
GEMINI_API_KEY=...
ELEVENLABS_API_KEY=...
```

Optional overrides: `GEMINI_TTS_MODEL` (default `gemini-3.8-flash-tts`),
`GEMINI_TTS_VOICE` (default `Kore`), `ELEVENLABS_MODEL` (default `eleven_v3`),
`ELEVENLABS_VOICE_ID`. Per-spec: `ttsProvider`, `geminiVoice`,
`elevenLabsVoice`. ElevenLabs free keys can use **premade voices only**;
Voice Library voices return `402 paid_plan_required`. Leave the voice ID unset
to auto-pick a premade voice. Free tier allows personal, non-commercial use.

List Edge voices:

```sh
node qcode-video.mjs --list-voices --language hi
```

## Scene planning rules

- Kids' story: 4–6 scenes, simple sentences, warm tone, happy ending.
- Tutorial/explainer: one idea per scene, 6–8 scenes, speak slowly.
- Promo/reel: 3–5 scenes, short punchy narration, 3–6 seconds each.
- Keep each image prompt concrete and visual: subject, action, setting, style,
  lighting, mood, and `no text`.
- Keep narration natural and speakable; avoid Markdown, emoji, and long URLs.
- For screenshots, set `"fit": "contain"` and use `image` instead of `imagePrompt`.
- For a 9:16 reel leave `width` and `height` at 720×1280.
- For a landscape tutorial set `"width": 1280, "height": 720`.

## Example prompt mapping

User: “Create a video with Hindi voice over story on a trending topic for a kids channel.”

Agent action: choose a small story (for example a brave star helping a child),
write 4 English image prompts, write 4 Hindi narration lines, set
`"language": "hi"`, run the command, and return the MP4 path.

## Notes

- The default image provider is Pollinations. Set `"imageProvider": "flow"` to
  use CoreX/Google Flow; the host must run `~/deepx-cdp-forward.sh` first.
- Use natural TTS only. Odia needs `GEMINI_API_KEY` or `ELEVENLABS_API_KEY`;
  Edge cannot speak Odia.
- Flow generation is driven by a real trusted click in the QCODE Browser because
  Google gates it with reCAPTCHA. Do not replace it with `element.click()`.
- Pollinations and Flow failures are retried/fallback automatically. Do not stop
  after one failure.
- Fonts are not burned into the video, so every language renders safely.
- If a scene image fails after retries, the tool uses a dark gradient so the video
  still completes.
- Keep the final MP4 under `/workspace/<project>/qcode-videos/`. Tell the user the
  exact path; the app can export it to Downloads/Files.
