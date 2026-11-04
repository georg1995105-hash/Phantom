from PIL import Image, ImageDraw, ImageFont
from pathlib import Path
import math, textwrap, os, zipfile

OUT=Path(__file__).resolve().parent.parent / 'docs' / 'mockups'; OUT.mkdir(parents=True,exist_ok=True)
W,H=540,1160
BG='#151619'; APP='#191a1e'; KB='#202124'; KEY='#3a3b40'; SPECIAL='#485169'; TEXT='#e9ebef'; MUTED='#a6a9b3'; PANEL='#292b30'; LINE='#393b42'; BLUE='#9aaed5'; SELECT='#3d4a63'; WHITE='#f3f4f7'
REG='/usr/share/fonts/opentype/inter/Inter-Regular.otf'; MED='/usr/share/fonts/opentype/inter/Inter-Medium.otf'; BOLD='/usr/share/fonts/opentype/inter/Inter-SemiBold.otf'; SYMBOL='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
if not Path(REG).exists(): REG='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
if not Path(MED).exists(): MED=REG
if not Path(BOLD).exists(): BOLD='/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf' if Path('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf').exists() else REG
if not Path(SYMBOL).exists(): SYMBOL=REG
import PIL

def f(sz=22,bold=False): return ImageFont.truetype(BOLD if bold else REG,sz)
def rgb(s): return s

class Screen:
 def __init__(self,light=False):
  self.light=light; self.bg='#f3f4f6' if light else BG; self.panel='#ffffff' if light else PANEL; self.text='#191a1d' if light else TEXT; self.muted='#63666e' if light else MUTED; self.key='#ffffff' if light else KEY; self.kb='#e6e9ee' if light else KB; self.special='#cbd5e8' if light else SPECIAL
  self.im=Image.new('RGB',(W,H),self.bg); self.d=ImageDraw.Draw(self.im)
  self.d.text((28,15),'18:30',font=f(20,True),fill=self.text);self.d.text((425,15),'4G  98%',font=f(17),fill=self.text)
 def rect(self,box,fill=None,r=14,outline=None,w=1):self.d.rounded_rectangle(box,radius=r,fill=fill or self.panel,outline=outline,width=w)
 def textat(self,pos,string,sz=23,col=None,bold=False,anchor=None):self.d.text(pos,string,font=ImageFont.truetype(SYMBOL,sz) if len(string)==1 and ord(string)>127 else f(sz,bold),fill=col or self.text,anchor=anchor)
 def line(self,xy,color=None,width=1):self.d.line(xy,fill=color or LINE,width=width)
 def nav(self):
  c=self.muted
  self.d.rounded_rectangle((248,1123,292,1127),radius=2,fill=c)
 def title(self,string,subtitle=None):
  self.textat((25,74),'‹',44,bold=False);self.textat((77,81),string,28,bold=True)
  if subtitle:self.textat((77,117),subtitle,16,self.muted)
 def sep(self,y):self.line((24,y,515,y),width=1)
 def pill(self,box,label,selected=False):
  self.rect(box,SELECT if selected else self.panel,20)
  self.textat(((box[0]+box[2])/2,(box[1]+box[3])/2),label,17,anchor='mm')
 def toggle(self,x,y,on=True):
  self.rect((x,y,x+55,y+31),SELECT if on else '#44454b',16)
  c='#c1d1f2' if on else '#b4b5bd';cx=x+39 if on else x+16
  self.d.ellipse((cx-11,y+5,cx+11,y+27),fill=c)
 def row(self,y,icon,label,subtitle=None,value=None,on=None,arrow=True,height=80):
  if y+height>1110: return
  vector_icon(self,icon,45,y+31,size=25)
  self.textat((82,y+13),label,22,bold=True)
  if subtitle:self.textat((82,y+44),subtitle,15,self.muted)
  if on is not None:self.toggle(446,y+23,on)
  elif value:self.textat((493,y+31),value,17,self.muted,anchor='rm')
  elif arrow:self.textat((495,y+25),'›',29,self.muted)
  self.sep(y+height-1)
 def save(self,name): self.nav();p=OUT/(name+'.png');self.im.save(p,optimize=True);return p

# Minimal vector icons, so UI does not depend on font emoji glyphs.
def vector_icon(s,kind,cx,cy,size=28):
 d=s.d; c=s.text if s.light else '#e2e5eb';k=kind
 if k in ['▣','clipboard']:
  d.rounded_rectangle((cx-10,cy-10,cx+10,cy+13),radius=2,outline=c,width=2)
  d.rounded_rectangle((cx-5,cy-14,cx+5,cy-7),radius=2,fill=c)
 elif k in ['♩','voice']:
  mic_icon(s,cx,cy-15)
 elif k in ['▦','keyboard']:
  d.rounded_rectangle((cx-15,cy-11,cx+15,cy+11),radius=3,outline=c,width=2)
  for u in range(4):
   for v in range(2): d.rectangle((cx-10+u*6,cy-7+v*7,cx-7+u*6,cy-4+v*7),fill=c)
  d.line((cx-8,cy+7,cx+8,cy+7),fill=c,width=2)
 elif k in ['☷','settings']:
  d.ellipse((cx-10,cy-10,cx+10,cy+10),outline=c,width=2)
  d.ellipse((cx-3,cy-3,cx+3,cy+3),outline=c,width=2)
  for i in range(8):
   a=i*math.pi/4;d.line((cx+int(10*math.cos(a)),cy+int(10*math.sin(a)),cx+int(14*math.cos(a)),cy+int(14*math.sin(a))),fill=c,width=2)
 elif k in ['◖','onehand']:
  d.rounded_rectangle((cx-8,cy-13,cx+8,cy+13),radius=3,outline=c,width=2)
  d.line((cx-5,cy+7,cx+5,cy+7),fill=c,width=2)
  d.line((cx-5,cy-7,cx-5,cy+3),fill=c,width=3)
 elif k in ['◎','lang']:
  d.ellipse((cx-12,cy-12,cx+12,cy+12),outline=c,width=2)
  d.ellipse((cx-5,cy-12,cx+5,cy+12),outline=c,width=2)
  d.line((cx-11,cy,cx+11,cy),fill=c,width=2)
 elif k in ['◉','themes']:
  d.ellipse((cx-12,cy-12,cx+12,cy+12),outline=c,width=2)
  d.ellipse((cx-6,cy-7,cx-2,cy-3),fill=c)
  d.ellipse((cx+3,cy-5,cx+7,cy-1),fill=c)
  d.ellipse((cx-7,cy+2,cx-3,cy+6),fill=c)
 elif k=='search':
  d.ellipse((cx-11,cy-11,cx+6,cy+6),outline=c,width=2)
  d.line((cx+5,cy+5,cx+15,cy+15),fill=c,width=2)
 else:
  s.textat((cx,cy),kind,size,c,anchor='mm')

# vector microphone icon instead of unsupported unicode emoji
def mic_icon(s,x,y):
 s.rect((x-6,y,x+6,y+18),s.text,6)
 s.d.arc((x-11,y+7,x+11,y+27),0,180,fill=s.text,width=2)
 s.d.line((x,y+27,x,y+32),fill=s.text,width=2)
 s.d.line((x-6,y+32,x+6,y+32),fill=s.text,width=2)

# draw keyboard / fixed Gboard-like layout, always IME only (no duplicate host input)
def icon_dots(s,x,y):
 for dx in [0,15]:
  for dy in [0,15]:s.rect((x+dx,y+dy,x+dx+10,y+dy+10),WHITE if not s.light else '#34353b',3)

def key(s,box,label,special=False,small=False):
 s.rect(box,s.special if special else s.key,9)
 s.textat(((box[0]+box[2])/2,(box[1]+box[3])/2),label,18 if small else 26,anchor='mm')

def keyboard(s,mode='normal',light=False,resizing=False):
 d=s.d; y=706; s.rect((0,y,540,1115),s.kb,0)
 # toolbar, 4-dot trigger, mic, 1 row only
 icon_dots(s,27,y+16);mic_icon(s,495,y+12)
 if mode=='normal':
  for i,tx in enumerate(['прод','продаю','продолжу']):
   x=72+i*139;s.textat((x+65,y+25),tx,19,s.text,anchor='mm')
   if i<2:s.line((x+136,y+12,x+136,y+43),s.muted,1)
 elif mode=='copied':
  # small compact card with real thumbnail and caption, no duplicate extra row
  s.rect((75,y+4,340,y+51),'#35373d' if not light else '#f1f3f5',21)
  draw_thumbnail(s,84,y+10,42,36,'shot')
  s.textat((136,y+18),'Скриншот',19); s.textat((315,y+16),'×',25,s.muted)
 elif mode=='voice':
  s.textat((85,y+25),'●  Слушаю…',21,s.text,anchor='lm');s.textat((435,y+24),'Готово',17,s.text,anchor='mm')
 elif mode=='gesture':
  for i,tx in enumerate(['привет','приветствую','приветик']):
   s.textat((95+i*133,y+24),tx,18,anchor='mm')
  s.textat((500,y+25),'●',19,s.muted,anchor='mm')
 # numbers
 cy=y+61; margin=14;gap=5; kw=(W-2*margin-9*gap)/10
 for i,n in enumerate('1234567890'):
  x=margin+i*(kw+gap);key(s,(x,cy,x+kw,cy+50),n)
 rows=['йцукенгшщзх','фывапролджэ','ячсмитьбю']
 for ri,letters in enumerate(rows):
  cy=y+119+ri*62
  if ri<2:
   gap=5; margin=12;kw=(W-2*margin-(len(letters)-1)*gap)/len(letters)
   for i,ch in enumerate(letters):
    x=margin+i*(kw+gap);key(s,(x,cy,x+kw,cy+55),ch)
  else:
   key(s,(12,cy,66,cy+55),'⇧',True)
   margin=70;right=470;gap=5;kw=(right-margin-(len(letters)-1)*gap)/len(letters)
   for i,ch in enumerate(letters):
    x=margin+i*(kw+gap);key(s,(x,cy,x+kw,cy+55),ch)
   key(s,(475,cy,529,cy+55),'⌫',True)
 cy=y+305
 parts=[(12,73,'?123',True),(91,134,'☺',False),(142,183,',',False),(191,230,'◎',False),(238,404,'Русский',False),(412,450,'.',True),(458,528,'↵',True)]
 for x1,x2,tx,sp in parts:key(s,(x1,cy,x2,cy+65),tx,sp,small=(tx=='Русский' or tx=='?123'))
 if mode=='gesture':
  # translucent swipe trace, does not occlude letters too much
  pts=[(122,y+250),(170,y+200),(225,y+250),(310,y+186),(361,y+250)]
  s.d.line(pts,fill='#abc1e3',width=5,joint='curve');
  for x1,y1 in [pts[0],pts[-1]]:s.d.ellipse((x1-5,y1-5,x1+5,y1+5),fill='#abc1e3')
 if resizing:
  s.d.rounded_rectangle((7,y-7,533,1096),radius=18,outline='#9aafcf',width=4)
  s.rect((236,y-15,306,y-1),'#9aafcf',5)

def draw_thumbnail(s,x,y,w,h,typ='shot'):
 if typ=='shot':
  ref=Path('/mnt/data/1000265423.png')
  if ref.exists():
   im=Image.open(ref).convert('RGB');iw,ih=im.size
   # Show the screenshot itself, not a generic picture icon
   im=im.crop((0,int(ih*.14),iw,int(ih*.9))).resize((int(w),int(h)),Image.Resampling.LANCZOS)
   s.im.paste(im,(int(x),int(y)))
  else:
   s.rect((x,y,x+w,y+h),'#536783',6)
   for i in range(3):s.line((x+6,y+5+i*8,x+w-7,y+5+i*8),'#9295a1',1)
 elif typ=='photo':
  s.rect((x,y,x+w,y+h),'#a6aab0',6)
  s.d.rectangle((x+2,y+h*.65,x+w-2,y+h-2),fill='#5d6e80')
  s.d.ellipse((x+w*.67,y+5,x+w*.82,y+16),fill='#e0d3b1')
  s.d.polygon([(x+2,y+h*.67),(x+w*.40,y+h*.25),(x+w*.60,y+h*.64)],fill='#596c73')

def app_area(s,explain=''):
 s.rect((0,54,W,705),APP if not s.light else '#f7f8fb',0)
 s.textat((25,83),'‹',35);s.textat((76,87),'Чат',26,bold=True)
 s.line((20,139,520,139),LINE)
 s.rect((30,196,392,285),'#313339' if not s.light else '#e6e8ed',17)
 s.textat((47,211),'Как дела? Получилось',20)
 s.textat((47,242),'разобраться с материалами?',20)
 s.rect((166,322,513,409),'#3c465b' if not s.light else '#d7e2f5',17)
 s.textat((181,337),'Да, почти всё готово.',20)
 s.textat((181,367),'Обсудим детали вечером.',20)
 if explain:s.textat((26,661),explain,17,s.muted)

def page_main(mode,name):
 s=Screen();app_area(s,'Поле сообщений относится к приложению, не к клавиатуре')
 keyboard(s,mode)
 return s.save(name)

def functions():
 s=Screen();app_area(s)
 y=706;s.rect((0,y,540,1114),KB,0)
 s.textat((25,y+19),'×',34);s.textat((269,y+31),'Функции клавиатуры',22,bold=True,anchor='mm')
 items=[('▣','Буфер обмена'),('▦','Размер'),('☷','Настройки'),('◖','Одна рука'),('◎','Языки'),('◉','Темы'),('♩','Голосовой ввод'),('✎','Изменить панель')]
 for i,(ic,tx) in enumerate(items):
  row,col=divmod(i,4); x=15+col*130; cy=y+81+row*128
  s.rect((x,cy,x+119,cy+118),'#303238',17)
  vector_icon(s,ic,x+59,cy+39,size=29)
  words=tx.split(' ')
  if len(tx)>13 and len(words)>1:
   s.textat((x+59,cy+76),words[0],15,anchor='mm');s.textat((x+59,cy+95),' '.join(words[1:]),15,anchor='mm')
  else:s.textat((x+59,cy+89),tx,15,anchor='mm')
 return s.save('03_functions')

def clipboard():
 s=Screen();app_area(s)
 y=706;s.rect((0,y,540,1114),KB,0)
 s.textat((22,y+12),'‹',39);s.textat((77,y+21),'Буфер обмена',23,bold=True)
 vector_icon(s,'search',493,y+34,size=29)
 s.line((14,y+61,526,y+61),LINE)
 tabs=[('Все',15,95),('Текст',104,195),('Фото',204,295),('Ссылки',304,412)]
 for i,(t,x1,x2) in enumerate(tabs):s.pill((x1,y+75,x2,y+114),t,i==0)
 s.textat((20,y+136),'Закреплённые',17,bold=True)
 s.rect((17,y+166,524,y+222),'#33353b',11);s.textat((32,y+175),'♢',26,s.muted);s.textat((77,y+185),'Домой приду, обсудим детали…',19)
 s.textat((20,y+238),'Недавние',17,bold=True)
 s.rect((17,y+271,524,y+341),'#33353b',11);draw_thumbnail(s,29,y+278,56,56)
 s.textat((97,y+287),'Скриншот',19,bold=True);s.textat((97,y+315),'18:27 · Изображение',15,s.muted);s.textat((494,y+290),'⋮',24,s.muted)
 s.rect((17,y+349,524,y+400),'#33353b',11);s.textat((31,y+358),'☷',21);s.textat((73,y+363),'Как дела? Получилось разобраться…',17)
 # bottom sheet would go beyond, but screenshot shows two recent items enough
 return s.save('04_clipboard')

def voice():
 s=Screen();app_area(s,'Речь вставляется непосредственно в поле приложения')
 y=706;s.rect((0,y,540,1114),KB,0)
 s.textat((26,y+22),'‹',35);s.textat((85,y+26),'Голосовой ввод',25,bold=True)
 s.rect((20,y+83,520,y+160),'#303238',17)
 s.textat((36,y+96),'●   Слушаю русскую речь…',21)
 s.textat((36,y+127),'Текст появляется в поле приложения',16,s.muted)
 # wave
 for i,h in enumerate([16,32,51,26,68,39,21,58,30,20,54,34,16,28,22]):
  x=78+i*28;s.rect((x,y+220-h/2,x+11,y+220+h/2),BLUE,7)
 s.textat((270,y+295),'Исправление речи включено',19,s.muted,anchor='mm')
 s.rect((30,y+325,253,y+383),'#3c3e43',20);s.textat((141,y+352),'Пауза',20,anchor='mm')
 s.rect((265,y+325,510,y+383),SELECT,20);s.textat((387,y+352),'Готово',20,anchor='mm')
 return s.save('05_voice')

def resize():
 s=Screen();app_area(s,'Изменение размера — прямо на клавиатуре')
 keyboard(s,'normal',resizing=True)
 s.rect((120,615,258,665),'#373941',22);s.textat((189,640),'Сброс',20,anchor='mm')
 s.rect((276,615,431,665),SELECT,22);s.textat((353,640),'Готово',20,anchor='mm')
 return s.save('06_resize')

def settings_home():
 s=Screen();s.title('Настройки клавиатуры')
 data=[('▦','Раскладка и размер','Языки, цифры, символы, высота'),('Aa','Ввод текста и жесты','Подсказки, автозамена, обучение'),('♩','Голосовой ввод','Распознавание, исправление, команды'),('▣','Буфер обмена','История, закрепление, превью'),('◉','Темы','Как в Gboard: светлая, тёмная, системная'),('◈','Конфиденциальность','Данные, словарь, очистка')]
 y=162
 for i,(ic,t,sub) in enumerate(data):s.row(y+i*113,ic,t,sub,height=105)
 return s.save('07_settings_home')

def settings_layout():
 s=Screen();s.title('Раскладка и размер');
 s.row(165,'▦','Размер клавиатуры','Настроить прямо на клавиатуре')
 s.row(254,'◫','Ряд цифр','Отдельный ряд над буквами',on=True)
 s.row(343,'⌗','Символы на клавишах','Дополнительные знаки',on=True)
 s.row(432,'▥','Клавиши','Отступы и крупные кнопки')
 s.row(521,'◖','Режим одной руки','Сдвиг клавиатуры влево или вправо')
 s.row(610,'◎','Языки','Русский, английский')
 s.row(699,'▢','Редактировать панель','Порядок нужных кнопок')
 return s.save('08_settings_layout')

def settings_typing():
 s=Screen();s.title('Ввод текста и жесты')
 s.row(165,'☷','Подсказки слов','В реальном времени',on=True)
 s.row(254,'✦','Умное предсказание','Подстраивается под привычки',on=True)
 s.row(343,'✓','Автоисправление','Отменяется одним действием',on=True)
 s.row(432,'〰','Ввод жестами','Писать проводя пальцем',on=True)
 s.row(521,'◀','Курсор пробелом','Провести пальцем по пробелу',on=True)
 s.row(610,'⇧','Заглавная буква','В начале предложения',on=True)
 s.row(699,'◈','Личный словарь','Слова, имена, сокращения')
 s.row(788,'↶','Выученные слова','Просмотреть и очистить')
 return s.save('09_settings_typing')

def settings_voice():
 s=Screen();s.title('Голосовой ввод')
 s.row(165,'◎','Язык речи','Русский; другие — по выбору')
 s.row(254,'♩','Распознавание','Показывать слова по мере речи',on=True)
 s.row(343,'✓','Исправлять речь','Без изменения смысла',on=True)
 s.row(432,'⋯','Знаки препинания','Автоматически',on=True)
 s.row(521,'✎','Команды редактирования','Удалить, заменить, отменить',on=True)
 s.row(610,'↗','Команда «Отправь»','Только в совместимых приложениях',on=False)
 s.row(699,'◉','Режим записи','Удерживать / нажать один раз')
 s.row(788,'◈','Обработка речи','Локальная и разрешённая онлайн')
 return s.save('10_settings_voice')

def settings_clipboard():
 s=Screen();s.title('Буфер обмена')
 s.row(165,'▣','Показывать скопированное','Компактное превью над клавишами',on=True)
 s.row(254,'▤','История буфера','Текст, ссылки и изображения',on=True)
 s.row(343,'♢','Закреплённые элементы','Хранятся до удаления')
 s.row(432,'⇅','Порядок элементов','Перетаскиванием')
 s.row(521,'◈','Конфиденциальность','Не запоминать чувствительные данные',on=True)
 s.row(610,'⌫','Очистить историю','Закреплённые сохранить')
 s.textat((27,741),'Снимок экрана появится при наличии',18,s.muted)
 s.textat((27,769),'его в системном буфере Android.',18,s.muted)
 return s.save('11_settings_clipboard')

def settings_themes():
 s=Screen();s.title('Тема');s.textat((26,152),'Оформление как у Gboard',18,s.muted)
 # sample themes 2x2 tiles: no neon
 themes=[('По системе',False,'system'),('Тёмная',True,'dark'),('Светлая',False,'light'),('Графитовая',True,'graphite')]
 for i,(name,dark,kind) in enumerate(themes):
  col,row=i%2,i//2; x=24+col*252;y=206+row*278
  fill='#222328' if dark else '#e8ebef';s.rect((x,y,x+240,y+227),'#292b30',18,outline='#8296ba' if i==0 else None,w=2)
  s.rect((x+9,y+9,x+231,y+172),fill,12)
  for j in range(3):
   for k in range(6):
    a=x+19+k*33;b=y+23+j*45
    s.rect((a,b,a+28,b+37),'#414247' if dark else '#ffffff',5)
  s.textat((x+15,y+187),name,19,bold=True)
  if i==0:s.textat((x+202,y+187),'✓',24,BLUE)
 s.textat((28,814),'Без эффектов и неоновой подсветки.',18,s.muted)
 return s.save('12_settings_themes')

def settings_privacy():
 s=Screen();s.title('Конфиденциальность')
 s.row(165,'◈','Обучение на устройстве','Подсказки без отправки набора',on=True)
 s.row(254,'⊘','Пароли и скрытые поля','Не запоминать и не подсказывать')
 s.row(343,'▣','Чувствительное в буфере','Не добавлять в историю')
 s.row(432,'♩','Записи голоса','Не сохранять по умолчанию')
 s.row(521,'◎','Онлайн-обработка','Только с разрешения',on=False)
 s.row(610,'⌫','Очистить личный словарь','Удалить выученные данные')
 s.row(699,'⌫','Очистить буфер','Удалить историю')
 return s.save('13_settings_privacy')

def settings_light():
 s=Screen(light=True);app_area(s,'')
 keyboard(s,'normal',light=True)
 return s.save('14_light_theme')

paths=[]
for mode,name in [('normal','01_keyboard'),('copied','02_compact_copy'),('gesture','02b_glide_input')]:paths.append(page_main(mode,name))
for fn in [functions,clipboard,voice,resize,settings_home,settings_layout,settings_typing,settings_voice,settings_clipboard,settings_themes,settings_privacy,settings_light]:paths.append(fn())
# contact sheets 5 image groups, 2 or 3 screens each
sheets=[('A_клавиатура_и_копирование',['01_keyboard','02_compact_copy','02b_glide_input']),('B_функции_и_буфер',['03_functions','04_clipboard']),('C_голос_и_размер',['05_voice','06_resize']),('D_основные_настройки',['07_settings_home','08_settings_layout','09_settings_typing']),('E_остальные_настройки',['10_settings_voice','11_settings_clipboard','12_settings_themes','13_settings_privacy','14_light_theme'])]
for name,keys in sheets:
 pad=24;gap=20;w=540; h=1160
 if len(keys)<=3:
  out=Image.new('RGB',(len(keys)*w+(len(keys)+1)*gap,h+2*pad),'#101114')
  for i,k in enumerate(keys):out.paste(Image.open(OUT/(k+'.png')),(gap+i*(w+gap),pad))
 else:
  scale=.72;rw=round(w*scale);rh=round(h*scale)
  cols=3;rows=math.ceil(len(keys)/cols)
  out=Image.new('RGB',(cols*rw+(cols+1)*gap,rows*rh+(rows+1)*gap),'#101114')
  for i,k in enumerate(keys):
   thumb=Image.open(OUT/(k+'.png')).resize((rw,rh),Image.Resampling.LANCZOS);out.paste(thumb,(gap+(i%cols)*(rw+gap),gap+(i//cols)*(rh+gap)))
 out.save(OUT/(name+'.png'),optimize=True)
# zip every single screen plus 5 overviews
with zipfile.ZipFile(str(OUT/'SmartVoiceKeyboard_UI_mockups_v1.zip'),'w',zipfile.ZIP_DEFLATED) as z:
 for p in sorted(OUT.glob('*.png')):z.write(p,arcname=p.name)
print('created',len(paths),'screens and',len(sheets),'overview PNGs');print('images',len(list(OUT.glob('*.png'))));print('zip bytes',(OUT/'SmartVoiceKeyboard_UI_mockups_v1.zip').stat().st_size)
