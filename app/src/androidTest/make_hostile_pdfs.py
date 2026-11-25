"""Writes the hostile and broken PDFs in assets/hostile/ for the step 7 tests.

Run from this directory: python3 make_hostile_pdfs.py
No dependencies. Encryption is the PDF 1.1 standard security handler (RC4, 40-bit, revision 2),
enough to make the renderer ask for a password.
"""
import hashlib
import os

OUT = os.path.join(os.path.dirname(__file__), "assets", "hostile")
PAD = bytes.fromhex("28BF4E5E4E758A4164004E56FFFA01082E2E00B6D0683E802F0CA9FE6453697A")
ID = bytes.fromhex("00112233445566778899aabbccddeeff")
CONTENT = b"0 g 50 50 200 300 re f BT /F1 24 Tf 60 400 Td (Volti) Tj ET"


def rc4(key, data):
    s = list(range(256))
    j = 0
    for i in range(256):
        j = (j + s[i] + key[i % len(key)]) % 256
        s[i], s[j] = s[j], s[i]
    i = j = 0
    out = bytearray()
    for b in data:
        i = (i + 1) % 256
        j = (j + s[i]) % 256
        s[i], s[j] = s[j], s[i]
        out.append(b ^ s[(s[i] + s[j]) % 256])
    return bytes(out)


def pdf(objects, trailer_extra=b""):
    """objects: list of bytes bodies, numbered from 1. Returns a complete PDF with a correct xref."""
    out = b"%PDF-1.4\n"
    offsets = []
    for n, body in enumerate(objects, 1):
        offsets.append(len(out))
        out += b"%d 0 obj\n" % n + body + b"\nendobj\n"
    xref = len(out)
    out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objects) + 1)
    out += b"".join(b"%010d 00000 n \n" % o for o in offsets)
    out += b"trailer<</Size %d/Root 1 0 R%s>>\nstartxref\n%d\n%%%%EOF\n" % (len(objects) + 1, trailer_extra, xref)
    return out


def stream(data):
    return b"<</Length %d>>stream\n" % len(data) + data + b"\nendstream"


def pages(media_boxes, contents=None):
    """Catalog, page tree, a font and one page per media box, each with its own content stream."""
    contents = contents or [CONTENT] * len(media_boxes)
    n = len(media_boxes)
    first_page = 4
    kids = b" ".join(b"%d 0 R" % (first_page + 2 * i) for i in range(n))
    objects = [b"<</Type/Catalog/Pages 2 0 R>>", b"<</Type/Pages/Kids[%s]/Count %d>>" % (kids, n),
               b"<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>"]
    for i, box in enumerate(media_boxes):
        objects.append(b"<</Type/Page/Parent 2 0 R/MediaBox[%s]/Contents %d 0 R/Resources<</Font<</F1 3 0 R>>>>>>"
                       % (box, first_page + 2 * i + 1))
        objects.append(stream(contents[i]))
    return objects


def encrypted(user_password, owner_password):
    p = -4
    owner_key = hashlib.md5((owner_password + PAD)[:32]).digest()[:5]
    o = rc4(owner_key, (user_password + PAD)[:32])
    key = hashlib.md5((user_password + PAD)[:32] + o + p.to_bytes(4, "little", signed=True) + ID).digest()[:5]
    u = rc4(key, PAD)
    objects = pages([b"0 0 595 842"])
    content_obj = 5
    object_key = hashlib.md5(key + content_obj.to_bytes(3, "little") + (0).to_bytes(2, "little")).digest()[:10]
    objects[content_obj - 1] = stream(rc4(object_key, CONTENT))
    objects.append(b"<</Filter/Standard/V 1/R 2/O<%s>/U<%s>/P %d>>" % (o.hex().encode(), u.hex().encode(), p))
    encrypt_ref = b"/Encrypt %d 0 R/ID[<%s><%s>]" % (len(objects), ID.hex().encode(), ID.hex().encode())
    return pdf(objects, encrypt_ref)


def form_bomb(depth=40):
    """Each form draws the next one twice: 2^depth draws from a few KB. Crashes PDFium in-process (native abort).
    Not used by HostileInputTest, it would kill the test process: it is the test case for the isolated renderer."""
    objects = [b"<</Type/Catalog/Pages 2 0 R>>", b"<</Type/Pages/Kids[3 0 R]/Count 1>>",
               b"<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]/Contents 4 0 R/Resources<</XObject<</F 5 0 R>>>>>>",
               stream(b"/F Do")]
    for k in range(depth):
        form = b"/Type/XObject/Subtype/Form/BBox[0 0 595 842]"
        if k < depth - 1:
            body = b"<<%s/Resources<</XObject<</F %d 0 R>>>>/Length 11>>stream\n/F Do /F Do\nendstream" % (form, 6 + k)
        else:
            body = b"<<%s/Length 16>>stream\n0 g 0 0 1 1 re f\nendstream" % form
        objects.append(body)
    return pdf(objects)


def main():
    os.makedirs(OUT, exist_ok=True)
    good = pdf(pages([b"0 0 595 842"] * 3))
    files = {
        "password.pdf": encrypted(b"secret", b"owner"),
        "truncated.pdf": good[: len(good) // 3],
        "not-a-pdf.pdf": b"This is a text file with a .pdf name.\n",
        "zero-pages.pdf": pdf([b"<</Type/Catalog/Pages 2 0 R>>", b"<</Type/Pages/Kids[]/Count 0>>"]),
        "zero-size-page.pdf": pdf(pages([b"0 0 0 0"])),
        "huge-page.pdf": pdf(pages([b"0 0 1000000000 1"])),
        "form-bomb.pdf": form_bomb(),
        "broken-page.pdf": pdf(pages([b"0 0 595 842"] * 3, [CONTENT, b"BT /F9 Tf ( q Q ] >> 1 0 obj", CONTENT])),
    }
    for name, data in files.items():
        with open(os.path.join(OUT, name), "wb") as f:
            f.write(data)
        print(f"{name}: {len(data)} bytes")


if __name__ == "__main__":
    main()
