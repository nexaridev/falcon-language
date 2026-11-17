# Images in Falcon

`libraries/pillow.fc` is an optional Pillow-inspired module for Falcon 0.6+, backed by Java ImageIO and Java2D. It needs no Python installation and works without a desktop display. It is an initial image toolkit, not the full Python Pillow API.

Copy `libraries/pillow.fc` into your project's `lib` folder, then:

```fc
use "lib/pillow.fc" as image

func main() {
    fix picture = image.new_image(640, 320, "#18243B")
    image.draw_text(picture, 30, 80, "Привіт!", 48, "#FFFFFF")
    image.save(picture, "greeting.png", "png")
    image.close(picture)
}
```

From the Falcon folder, `falcon run examples/image_card.fc` creates a PNG greeting card and a resized JPEG in your current directory. To send a generated image through a bot, save it first, then call `telebot.send_photo(bot, chatId, "greeting.png", "Hello")`.

## Behavior

- Images are `int` handles. `new_image` and `open` create handles. `copy`, `resize`, `crop`, `rotate`, `flip`, `grayscale`, `brightness`, and `blur` return new images without changing the original.
- Drawing, `put_pixel`, and `paste` modify the destination. `save` writes a file and may overwrite an existing one. Parent directories must exist.
- Colors are `#RRGGBB` or `#RRGGBBAA`; the last two digits specify alpha. `get_pixel` always returns uppercase `#RRGGBBAA`.
- Coordinates start at the top-left. Crop and rectangle bounds use left/top inclusive, right/bottom exclusive. Crops must fit inside the source. Pixel operations reject out-of-bounds coordinates; drawing and pasting clip at image edges.
- `resize` uses bilinear interpolation. `rotate` accepts multiples of 90 degrees, counterclockwise, and expands dimensions for quarter turns. `flip` accepts `"horizontal"` or `"vertical"`.
- `draw_text` uses a plain SansSerif font with a top-left text position and a size of 1–512. Cyrillic uses fonts available to Java on your machine; glyph coverage for other scripts/emoji depends on installed fonts. It is single-line text.
- `rectangle` and `ellipse` are filled; `line` draws a one-pixel line. `paste` uses source-over alpha composition and requires a different source handle.
- `grayscale` preserves alpha. `brightness` accepts 0–10 (1 is unchanged). `blur` is a 3×3 box blur with clamped edges and alpha-weighted colors.
- `open` supports formats available in Java ImageIO, including PNG/JPEG/GIF/BMP. It reads only the first frame and does not apply EXIF orientation. `save` supports PNG and JPEG (`jpg` is accepted); JPEG flattens transparency onto white. Metadata is not retained.
- Images are limited to 16 million pixels each and 64 million pixels retained per program. `close` frees a handle; using it again is an error. All handles are released when the program finishes. Temporary decoding/filter buffers use additional memory.

No animation editing, SVG, WebP, arbitrary-angle rotation, advanced typography, EXIF editing, or color-profile management is included yet.

Reference: [Pillow Image API](https://pillow.readthedocs.io/en/stable/reference/Image.html).

## Function signatures

```fc
func new_image(imageWidth: int, imageHeight: int, color: string) -> int
func open(filename: string) -> int
func save(image: int, filename: string, format: string)
func width(image: int) -> int
func height(image: int) -> int
func close(image: int)
func copy(image: int) -> int
func resize(image: int, targetWidth: int, targetHeight: int) -> int
func crop(image: int, left: int, top: int, right: int, bottom: int) -> int
func rotate(image: int, degrees: int) -> int
func flip(image: int, axis: string) -> int
func grayscale(image: int) -> int
func blur(image: int) -> int
func brightness(image: int, factor: number) -> int
func get_pixel(image: int, x: int, y: int) -> string
func put_pixel(image: int, x: int, y: int, color: string)
func paste(image: int, source: int, x: int, y: int)
func draw_text(image: int, x: int, y: int, value: string, size: int, color: string)
func rectangle(image: int, left: int, top: int, right: int, bottom: int, color: string)
func ellipse(image: int, left: int, top: int, right: int, bottom: int, color: string)
func line(image: int, x1: int, y1: int, x2: int, y2: int, color: string)
```
