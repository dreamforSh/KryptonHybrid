"""Generates the line icons used by README.md and the changelogs (docs/assets/icons/*.svg).

Run from the repository root: python docs/assets/make_icons.py
"""
import os

BLUE = '#3b82f6'
GREEN = '#22c55e'
AMBER = '#f59e0b'
VIOLET = '#8b5cf6'
SKY = '#0ea5e9'
PINK = '#ec4899'

# name -> (color, inner svg)
ICONS = {
    'info': (BLUE, '<circle cx="12" cy="12" r="9"/><path d="M12 11v5"/><circle cx="12" cy="7.8" r="0.4" fill="{c}"/>'),
    'warning': (AMBER, '<path d="M12 3.5 21.5 20h-19z"/><path d="M12 10v4"/><circle cx="12" cy="17" r="0.4" fill="{c}"/>'),
    'added': (GREEN, '<rect x="3" y="3" width="18" height="18" rx="4"/><path d="M12 8v8M8 12h8"/>'),
    'fixed': (GREEN, '<circle cx="12" cy="12" r="9"/><path d="M8 12.5l2.7 2.7L16 9.8"/>'),
    'zap': (VIOLET, '<path d="M13 2.5 4.5 14h7l-1 7.5L19 10h-7z"/>'),
    'sliders': (SKY, '<path d="M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12"/><circle cx="16" cy="6" r="2"/><circle cx="10" cy="12" r="2"/><circle cx="18" cy="18" r="2"/>'),
    'package': (BLUE, '<path d="M12 2.5 20.5 7v10L12 21.5 3.5 17V7z"/><path d="M3.5 7 12 11.5 20.5 7M12 11.5v10"/>'),
    'book': (BLUE, '<path d="M4 4.5A1.5 1.5 0 0 1 5.5 3H20v15H5.5A1.5 1.5 0 0 0 4 19.5z"/><path d="M4 19.5A1.5 1.5 0 0 0 5.5 21H20v-3"/><path d="M8 7h8M8 10.5h6"/>'),
    'code': (BLUE, '<path d="M8 7l-5 5 5 5M16 7l5 5-5 5M13.5 4l-3 16"/>'),
    'layers': (BLUE, '<path d="M12 3 21 7.5 12 12 3 7.5z"/><path d="M3 12 12 16.5 21 12"/><path d="M3 16.5 12 21 21 16.5"/>'),
    'grid': (BLUE, '<rect x="3" y="3" width="18" height="18" rx="2"/><path d="M3 9h18M3 15h18M9 3v18M15 3v18"/>'),
    'sun': (BLUE, '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>'),
    'clock': (BLUE, '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.2 2"/>'),
    'activity': (BLUE, '<path d="M3 12h4l3-7.5 4 15 3-7.5h4"/>'),
    'cpu': (BLUE, '<rect x="6" y="6" width="12" height="12" rx="2"/><rect x="9.5" y="9.5" width="5" height="5"/><path d="M9.5 2.5V6M14.5 2.5V6M9.5 18v3.5M14.5 18v3.5M2.5 9.5H6M2.5 14.5H6M18 9.5h3.5M18 14.5h3.5"/>'),
    'terminal': (BLUE, '<rect x="2.5" y="4" width="19" height="16" rx="2"/><path d="M7 9l3 3-3 3M12.5 15H17"/>'),
    'shield': (BLUE, '<path d="M12 2.5 20 5.5v6c0 5-3.4 8.8-8 10.5-4.6-1.7-8-5.5-8-10.5v-6z"/><path d="M8.5 12l2.5 2.5 4.5-5"/>'),
    'chart': (BLUE, '<path d="M3 3v18h18"/><path d="M7.5 16v-4M12 16V8M16.5 16V9"/>'),
    'download': (BLUE, '<path d="M12 3v12M7 10l5 5 5-5M4 20h16"/>'),
    'tag': (BLUE, '<path d="M3 12V4a1 1 0 0 1 1-1h8l9 9-9 9z"/><circle cx="7.5" cy="7.5" r="1.5"/>'),
    'gauge': (BLUE, '<path d="M4.2 17.5a9 9 0 1 1 15.6 0"/><path d="M12 14l4-5"/><circle cx="12" cy="14" r="1.2"/>'),
    'heart': (PINK, '<path d="M12 20s-7.5-4.6-9.2-9A4.8 4.8 0 0 1 12 6.4 4.8 4.8 0 0 1 21.2 11c-1.7 4.4-9.2 9-9.2 9z"/>'),
    'scale': (BLUE, '<path d="M12 3v18M7 21h10M5 7h14"/><path d="M5 7l-3 6a3 3 0 0 0 6 0z"/><path d="M19 7l-3 6a3 3 0 0 0 6 0z"/>'),
    'globe': (BLUE, '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c3 3.2 3 14.8 0 18M12 3c-3 3.2-3 14.8 0 18"/>'),
    'plug': (BLUE, '<path d="M9 2.5v4.5M15 2.5v4.5"/><path d="M6 7h12v3.5a6 6 0 0 1-12 0z"/><path d="M12 16.5v5"/>'),
    'monitor': (BLUE, '<rect x="2.5" y="4" width="19" height="13" rx="2"/><path d="M8 21h8M12 17v4"/>'),
    'list': (BLUE, '<path d="M9 6h11M9 12h11M9 18h11"/><circle cx="4.5" cy="6" r="0.6" fill="{c}"/><circle cx="4.5" cy="12" r="0.6" fill="{c}"/><circle cx="4.5" cy="18" r="0.6" fill="{c}"/>'),
    'history': (BLUE, '<path d="M3.5 12a8.5 8.5 0 1 0 2.5-6"/><path d="M3 3.5V8h4.5"/><path d="M12 8v4.5l3 1.8"/>'),
}

TEMPLATE = ('<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" '
            'fill="none" stroke="{c}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">{body}</svg>\n')

LOGO = '''<svg xmlns="http://www.w3.org/2000/svg" width="128" height="128" viewBox="0 0 128 128">
  <defs>
    <linearGradient id="tile" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#22d3ee"/>
      <stop offset="1" stop-color="#6366f1"/>
    </linearGradient>
  </defs>
  <rect x="4" y="4" width="120" height="120" rx="26" fill="url(#tile)"/>
  <rect x="4" y="4" width="120" height="120" rx="26" fill="none" stroke="#ffffff" stroke-opacity="0.25" stroke-width="2"/>
  <text x="18" y="34" font-family="Segoe UI, Helvetica, Arial, sans-serif" font-size="18" font-weight="600" fill="#ffffff" fill-opacity="0.85">36</text>
  <text x="64" y="86" text-anchor="middle" font-family="Segoe UI, Helvetica, Arial, sans-serif" font-size="54" font-weight="700" fill="#ffffff">Kr</text>
  <path d="M34 104h60" stroke="#ffffff" stroke-opacity="0.6" stroke-width="4" stroke-linecap="round"/>
</svg>
'''

root = os.path.join(os.path.dirname(os.path.abspath(__file__)))
icons_dir = os.path.join(root, 'icons')
os.makedirs(icons_dir, exist_ok=True)
for name, (color, body) in ICONS.items():
    with open(os.path.join(icons_dir, name + '.svg'), 'w', encoding='utf-8', newline='\n') as f:
        f.write(TEMPLATE.format(c=color, body=body.replace('{c}', color)))
with open(os.path.join(root, 'logo.svg'), 'w', encoding='utf-8', newline='\n') as f:
    f.write(LOGO)
print(len(ICONS), 'icons written')
