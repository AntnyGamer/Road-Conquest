# Android lint regression gate

Debug and release CI run lint with `warningsAsErrors` and the checked-in baseline.
Fix new findings instead of updating the baseline to make a build pass. Refresh it
only after reviewing every changed entry; remove entries when their code is fixed.
Android documents this workflow at
https://developer.android.com/studio/write/lint#snapshot.

The November 2026 cleanup removes the reported hardcoded layout text, concatenated
TextView strings and quantity labels. Those findings are not baseline exceptions.
The baseline records the remaining existing findings at individual locations:

- `ApplySharedPref`: synchronous preference writes include deletion, consent,
  recovery and export completion boundaries. Preserve their durability; an
  asynchronous write is not an equivalent cleanup.
- `UseKtx` and `LogNotTimber`: style suggestions, not correctness fixes. The app
  uses Android logging without a Timber dependency. Changing spelling or adding a
  logging library does not improve these paths.
- `UnusedAttribute`: Android 13 predictive-back opt-in is deliberately present;
  Android 12 ignores the newer attribute and uses the existing fallback.
- `ClickableViewAccessibility`: the replay map's touch observer only coordinates
  parent scrolling and returns false. MapLibre retains gesture/click ownership;
  synthesizing a click here would change behavior.
- `ButtonStyle`, `SmallSp` and `TypographyDashes`: retain the existing styled
  controls, ad attribution and English copy. These need a visual/accessibility
  design review, rather than mechanical substitutions.
- `MergeRootFrame`, `InefficientWeight`, `Overdraw` and `TooManyViews`: existing
  layout suggestions, retained pending device profiling. Root IDs, theme
  backgrounds, bounded tour height and live Settings controls have behavior that
  a blind hierarchy replacement could change.

`RoadMessagingService` separately suppresses the obsolete token-refresh warning
with an explanation at the class: current FCM installation-ID registration uses
`onRegistered`, and the app does not request legacy registration tokens. No lint
issue category is globally disabled.
