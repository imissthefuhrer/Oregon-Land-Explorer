"""Pin the accepted PR #4 bytes for a fair before/after comparison."""
from pathlib import Path
import hashlib

EXPECTED={
    'oregon-land-explorer-expanded.apk':'038af026e4fdf64fe7eee372657bbe9954dd12320442e5caf782d86a4b704e05',
    'gervais-countryside.olepack':'82330890fb6952e7192d2e660eb205c1b58506576259295456c405e285f0399d',
    'silver-falls.olepack':'11d9c00a49372779adda84e94076b19e602db1d8bd52a055db12ed4744ef8581',
    'gervais-original-parcels.olepack':'59d526b1d67a241ec071323dd952ccd14375edc3751adaf1e70d5d286709f397',
    'gervais-original-aerial.olepack':'a2f8b7a6dfec4e7593ec95f76701b88e3b4e3ae466bbc082d6b52fc3eea0e3dc',
    'willamette-silver-outdoors.olepack':'bdbb019ce04c80dc5c9d90b0f49521f7d2bab390cc707be41fa13d40b6ed1fad',
}
for name,digest in EXPECTED.items():
    with (Path('work/expanded')/name).open('rb') as f:
        assert hashlib.file_digest(f,'sha256').hexdigest()==digest, name
print('Verified all six unchanged PR #4 input files')
