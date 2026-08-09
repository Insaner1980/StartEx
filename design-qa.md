# Design QA

Reference-image content was used only for visual direction. Its balances, tokens, scores, addresses, and activity were not copied.

## Comparison

- Verified at a 412 x 915 dp phone viewport with a recorded Robolectric render.
- Compared the render beside the supplied reference collage in one image.
- Matched the near-black background, restrained slate cards, thin outlines, compact hierarchy, and green health/action accent.
- Kept the first-run and empty states honest: unavailable values and absent positions are labeled instead of filled with sample data.

## Finding fixed

The first render exposed an inherited black title on the dark background. `ScreenHeader` and the loading screen now set the theme's explicit on-background color, and the recorded render was regenerated and rechecked.

## Runtime check

The debug APK was installed and launched on an API 36 phone emulator. The accessibility hierarchy showed the complete first onboarding screen, including its progress, risk disclosure, acknowledgement, and disabled Continue action. Android screenshots become intentionally black after launch because `FLAG_SECURE` is enabled before Compose content; the recorded JVM render is therefore the visual comparison artifact.
