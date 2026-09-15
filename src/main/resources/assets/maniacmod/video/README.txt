══════════════════════════════════════════════════════════════
 ВІДЕОФАЙЛИ ДЛЯ ManiacMod — БЕЗ FFMPEG
══════════════════════════════════════════════════════════════

Помісти сюди: intro.zip

Структура ZIP:
  frame_0001.png
  frame_0002.png
  ...

Як конвертувати відео (Python):
  pip install moviepy
  python3 -c "
from moviepy.editor import VideoFileClip
VideoFileClip('intro.mp4').write_images_sequence('frames/frame_%04d.png', fps=24)
  "
  zip -j intro.zip frames/frame_*.png

Рекомендації:
  Роздільність: 854x480
  FPS: 24
  Формат: PNG без прозорості

Відео буде автоматично завантажено при першому запуску гри.
Кешується в пам'яті — наступні старти швидші.
══════════════════════════════════════════════════════════════
