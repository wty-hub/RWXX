"""Use the pinned four-mode comparison; historical cached / load-corrected ratios are obsolete.

The same --tag / --runs / --original / --extra entry points now require fresh outputs, retain
all post-warmup windows, and use the original post-swap probe at the same physical resolution.
"""
from pinned_interaction_comparison import main

if __name__ == '__main__':
    raise SystemExit(main())
