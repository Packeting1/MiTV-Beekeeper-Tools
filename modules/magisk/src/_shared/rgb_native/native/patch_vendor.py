"""Apply reviewed fixed byte replacements only to the exact supported vendor library."""
from pathlib import Path
import hashlib,json

def patch(source,destination):
    record=json.loads(Path(__file__).with_name('native-patch.json').read_text(encoding='utf8'))
    data=bytearray(Path(source).read_bytes())
    if hashlib.sha256(data).hexdigest()!=record['original_sha256']:raise ValueError('Unsupported original vendor library SHA-256')
    for item in [record,*record['additional_patches']]:
        offset=item['file_offset'];before=bytes.fromhex(item['original_hex']);after=bytes.fromhex(item['patched_hex'])
        if len(before)!=len(after) or data[offset:offset+len(before)]!=before:raise ValueError('Vendor patch preimage mismatch')
        data[offset:offset+len(after)]=after
    if hashlib.sha256(data).hexdigest()!=record['patched_sha256']:raise ValueError('Patched vendor library SHA-256 mismatch')
    Path(destination).parent.mkdir(parents=True,exist_ok=True);Path(destination).write_bytes(data)
    return record
