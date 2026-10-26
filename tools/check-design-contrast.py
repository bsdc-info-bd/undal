#!/usr/bin/env python3
"""Check opaque Material foreground/background pairs from the shipping Kotlin theme.

Not a screenshot/accessibility audit: gradients, opacity, photos, focus, and
individual component overrides still need visual/device verification.
"""
import pathlib
import re

root = pathlib.Path(__file__).resolve().parent.parent
source = (root / 'app/src/main/java/com/undal/syl/app/core/theme/UndalColors.kt').read_text()
palette = dict(re.findall(r'val\s+(\w+)\s*=\s*Color\(0xFF([A-Fa-f0-9]{6})\)', source))


def colour(expression):
    if expression == 'Color.White':
        return 'FFFFFF'
    if expression.startswith('UndalPalette.'):
        return palette[expression.split('.')[1]]
    match = re.fullmatch(r'Color\(0xFF([A-Fa-f0-9]{6})\)', expression)
    if not match:
        raise ValueError(f'Unrecognised theme colour: {expression}')
    return match.group(1)


def luminance(value):
    channels = [int(value[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    linear = [v / 12.92 if v <= .04045 else ((v + .055) / 1.055) ** 2.4 for v in channels]
    return sum(c * w for c, w in zip(linear, (.2126, .7152, .0722)))


def ratio(a, b):
    low, high = sorted((luminance(a), luminance(b)))
    return (high + .05) / (low + .05)


pairs = [
    ('onPrimary', 'primary'), ('onPrimaryContainer', 'primaryContainer'),
    ('onSecondary', 'secondary'), ('onSecondaryContainer', 'secondaryContainer'),
    ('onTertiary', 'tertiary'), ('onBackground', 'background'),
    ('onSurface', 'surface'), ('onSurfaceVariant', 'surfaceVariant'),
    ('onError', 'error'), ('onErrorContainer', 'errorContainer'),
    ('primary', 'surface'), ('secondary', 'surface'),
]
failures = []
checks = 0
for name in ('UndalLightScheme', 'UndalDarkScheme'):
    body = re.search(rf'val {name}: ColorScheme = \w+\((.*?)\n\)', source, re.S).group(1)
    values = {key: colour(value.strip()) for key, value in re.findall(r'^\s*(\w+) = (.*?),?$', body, re.M)}
    for foreground, background in pairs + [('outline', 'surface')]:
        minimum = 3 if foreground == 'outline' else 4.5
        result = ratio(values[foreground], values[background])
        checks += 1
        if result < minimum:
            failures.append(f'{name} {foreground}/{background}: {result:.2f}:1 < {minimum}:1')
if failures:
    raise SystemExit('\n'.join(failures))
print(f'Design contrast: {checks} shipping light/dark theme pairs pass (text 4.5:1, controls 3:1).')
