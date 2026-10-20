package io.github.prostoodin1.cameradeck;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.File;
import java.net.*;
import java.util.*;

public class MainActivity extends Activity {
    static final int BLACK=0xff030303, PANEL=0xff171717, YELLOW=0xffffd76b, WHITE=0xfff4f4f4, MUTED=0xffa5a5a5;
    final Handler ui=new Handler(Looper.getMainLooper());
    TextView state,telemetry,notice,center,code,fingerprint;
    ImageView preview;
    TextureView nativeView;
    CameraEngine attachedEngine;
    SurfaceTexture attachedTexture;
    TextView storage;
    long nextStorageRefresh;
    Button start,shutter,mode,isoReadout,shutterReadout,formatReadout,install;
    CheckBox localPreview,eco;
    boolean resumed,commandPending,showPreview=true;
    Bitmap displayedFrame;
    TextView connection;
    long sequence=-1;
    OrientationEventListener orientation;
    AlertDialog pairing;
    final Runnable refresh=new Runnable(){public void run(){update();ui.postDelayed(this,500);}};
    final Runnable frames=new Runnable(){public void run(){renderPreview();ui.postDelayed(this,100);}};

    int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    GradientDrawable panel(int fill,int stroke,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));d.setStroke(dp(1),stroke);return d;}
    TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(dp(6),dp(6),dp(6),dp(6));return t;}
    void buttonStyle(Button b,boolean primary){b.setTextColor(primary?BLACK:YELLOW);b.setBackground(panel(primary?YELLOW:PANEL,primary?YELLOW:0xff424242,14));b.setAlpha(b.isEnabled()?1f:0.4f);}
    Button button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setTextSize(13);b.setAllCaps(false);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setPadding(dp(8),dp(6),dp(8),dp(6));buttonStyle(b,false);b.setOnClickListener(v->action.run());return b;}
    LinearLayout column(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);return c;}
    LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);return r;}
    void cell(LinearLayout row,View view,int height){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(height),1);p.setMargins(dp(3),dp(3),dp(3),dp(3));row.addView(view,p);}
    LinearLayout dialogBody(){LinearLayout c=column();c.setPadding(dp(14),dp(8),dp(14),dp(10));return c;}
    ScrollView scroll(LinearLayout c){ScrollView s=new ScrollView(this);s.setFillViewport(true);s.addView(c);return s;}
    void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    CameraEngine engine(){CameraService s=CameraService.active;return s==null?null:s.engine;}
    CheckBox check(String label,boolean value){CheckBox c=new CheckBox(this);c.setText(label);c.setTextColor(WHITE);c.setTextSize(13);c.setChecked(value);c.setButtonTintList(ColorStateList.valueOf(YELLOW));return c;}

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        getWindow().setStatusBarColor(BLACK);getWindow().setNavigationBarColor(BLACK);
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(BLACK);setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            int top,bottom,left,right;
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());top=bars.top;bottom=bars.bottom;left=bars.left;right=bars.right;}
            else{top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();left=insets.getSystemWindowInsetLeft();right=insets.getSystemWindowInsetRight();}
            v.setPadding(left,top,right,bottom);return insets;
        });
        FrameLayout finder=new FrameLayout(this);root.addView(finder,new FrameLayout.LayoutParams(-1,-1));
        nativeView=new TextureView(this);nativeView.setOpaque(false);finder.addView(nativeView,new FrameLayout.LayoutParams(-1,-1));
        nativeView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
            public void onSurfaceTextureAvailable(SurfaceTexture t,int w,int h){attachPreview();}
            public void onSurfaceTextureSizeChanged(SurfaceTexture t,int w,int h){transformPreview();}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture t){if(attachedEngine!=null&&attachedTexture==t){attachedEngine.detachLocal(t);attachedEngine=null;attachedTexture=null;return false;}return true;}
            public void onSurfaceTextureUpdated(SurfaceTexture t){}
        });
        preview=new ImageView(this);preview.setBackgroundColor(BLACK);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);finder.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        center=text("Ваша камера. Ваш кадр.\nНажмите «Включить»",17,MUTED);center.setGravity(Gravity.CENTER);finder.addView(center,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout top=column();top.setPadding(dp(16),dp(8),dp(16),dp(8));top.setBackground(panel(0xc0060606,0x22444444,22));
        LinearLayout header=row();TextView brand=text("AURORA",16,YELLOW);brand.setLetterSpacing(.17f);header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        start=button("Включить",this::toggle);header.addView(start,new LinearLayout.LayoutParams(dp(112),dp(44)));
        Button link=button("Подключить",this::pairingSettings);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(116),dp(44));lp.setMargins(dp(8),0,0,0);header.addView(link,lp);top.addView(header);
        connection=text("Телефон и компьютер · одна Wi-Fi сеть",11,MUTED);connection.setOnClickListener(v->pairingSettings());top.addView(connection);
        state=text("Камера выключена",11,YELLOW);top.addView(state);root.addView(top,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        LinearLayout bottom=column();bottom.setPadding(dp(14),dp(8),dp(14),dp(12));bottom.setBackground(panel(0xd5060606,0x22555555,24));
        storage=text("",11,MUTED);storage.setOnClickListener(v->storageSettings());bottom.addView(storage);
        notice=text("",11,MUTED);notice.setMaxLines(2);bottom.addView(notice);
        LinearLayout controls=row();mode=button("Фото",()->{CameraEngine e=engine();if(e!=null)command("capture_config","mode",e.captureMode.equals("photo")?"video":"photo");});
        shutter=button("●",()->{CameraEngine e=engine();if(e!=null)command(e.captureMode.equals("photo")?"photo":e.recording?"record_stop":"record_start");});shutter.setTextSize(22);
        cell(controls,mode,52);cell(controls,shutter,60);cell(controls,button("Объектив",this::lensSettings),52);bottom.addView(controls);
        LinearLayout menus=row();formatReadout=button("Качество ▴",this::captureSettings);cell(menus,formatReadout,44);cell(menus,button("Экспозиция ▴",this::sensorSettings),44);cell(menus,button("Ещё ▴",this::powerSettings),44);bottom.addView(menus);
        telemetry=text("",11,MUTED);telemetry.setGravity(Gravity.CENTER);bottom.addView(telemetry);root.addView(bottom,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        isoReadout=button("",this::sensorSettings);shutterReadout=button("",this::sensorSettings);
        localPreview=check("Показывать камеру",true);localPreview.setOnCheckedChangeListener((v,value)->{showPreview=value;CameraEngine e=engine();if(!value&&e!=null)e.hideLocal();sequence=-1;});
        orientation=new OrientationEventListener(this){public void onOrientationChanged(int degrees){
            CameraEngine e=engine();if(degrees<0||e==null||e.recording)return;
            e.deviceRotation=((degrees+45)/90*90)%360;
            try{int sensor=e.characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION);boolean front=e.characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)==android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT;e.rotation=(sensor+(front?e.deviceRotation:-e.deviceRotation)+360)%360;}catch(Exception ignored){}
        }};
    }


    AlertDialog.Builder dialog(){
        return new AlertDialog.Builder(this){
            @Override public AlertDialog create(){
                AlertDialog d=super.create();
                d.setOnShowListener(ignored->{
                    Window w=d.getWindow();if(w==null)return;
                    boolean blur=Build.VERSION.SDK_INT>=31&&getSystemService(WindowManager.class).isCrossWindowBlurEnabled();
                    w.setBackgroundDrawable(panel(blur?0xdc171717:PANEL,0xff454545,24));
                    if(Build.VERSION.SDK_INT>=31&&blur){w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);w.setBackgroundBlurRadius(dp(24));WindowManager.LayoutParams a=w.getAttributes();a.setBlurBehindRadius(dp(20));w.setAttributes(a);}
                    w.setDimAmount(0.5f);w.setGravity(Gravity.BOTTOM);w.setLayout(-1,-2);
                });return d;
            }
        };
    }
    void lensSettings(){
        CameraEngine e=engine();if(e==null||!e.ready){toast("Сначала включите камеру");return;}
        if(e.recording||e.photoPending){toast("Остановите запись и дождитесь сохранения фото");return;}
        try{
            JSONArray cameras=e.status().getJSONArray("cameras");String[] labels=new String[cameras.length()];int selected=0;
            for(int i=0;i<cameras.length();i++){JSONObject c=cameras.getJSONObject(i);labels[i]=(c.optInt("facing")==0?"Фронтальная":c.optInt("facing")==1?"Основная":"Внешняя")+" · "+c.getString("id");if(c.getString("id").equals(e.cameraId))selected=i;}
            dialog().setTitle("Выберите объектив").setSingleChoiceItems(labels,selected,(d,index)->{try{command("camera","id",cameras.getJSONObject(index).getString("id"));sequence=-1;}catch(Exception ex){toast(ex.getMessage());}d.dismiss();}).setNegativeButton("Закрыть",null).show();
        }catch(Exception ex){toast(ex.getMessage());}
    }

    void command(String action,Object... values){
        CameraEngine e=engine();if(e==null){toast("Сначала включите камеру");return;}
        if(commandPending)return;commandPending=true;
        new Thread(()->{try{JSONObject body=new JSONObject().put("action",action);for(int i=0;i<values.length;i+=2)body.put(values[i].toString(),values[i+1]);e.command(body);}catch(Exception ex){Throwable cause=ex.getCause()==null?ex:ex.getCause();ui.post(()->toast(String.valueOf(cause.getMessage())));}finally{ui.post(()->{commandPending=false;update();});}},"CameraDeck-local-control").start();
    }

    void pairingSettings(){
        LinearLayout body=dialogBody();EditText name=new EditText(this);name.setSingleLine();name.setTextColor(YELLOW);name.setText(getSharedPreferences("deck",0).getString("name",Build.MODEL));body.addView(text("ИМЯ КАМЕРЫ",10,MUTED));body.addView(name);
        code=text("Камера выключена",28,YELLOW);code.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);body.addView(code);
        fingerprint=text("",12,WHITE);fingerprint.setTextIsSelectable(true);body.addView(fingerprint);
        body.addView(text("Сравните весь отпечаток на Windows и телефоне, затем введите PIN в пульте.",12,MUTED));
        body.addView(button("НОВЫЙ PIN",()->{CameraService s=CameraService.active;if(s!=null)s.rotatePin();update();}));
        body.addView(button("ОТОЗВАТЬ ДОСТУП КОМПЬЮТЕРОВ",()->{CameraService s=CameraService.active;if(s!=null)s.revoke();update();}));
        pairing=dialog().setTitle("ПОДКЛЮЧЕНИЕ").setView(scroll(body)).setPositiveButton("СОХРАНИТЬ",(d,w)->{String value=name.getText().toString().trim();if(!value.isEmpty()&&value.length()<=60)getSharedPreferences("deck",0).edit().putString("name",value).apply();}).setNegativeButton("ЗАКРЫТЬ",null).create();pairing.show();update();
    }

    void powerSettings(){
        LinearLayout body=dialogBody();body.addView(button("Папка сохранения",this::storageSettings));body.addView(button("Все возможности камеры",this::capabilityDetails));CheckBox view=check("Локальный видоискатель",showPreview);view.setOnCheckedChangeListener((v,value)->localPreview.setChecked(value));body.addView(view);eco=check("Экономный поток · до 12 кадров/с",getSharedPreferences("deck",0).getBoolean("eco",false));
        eco.setOnCheckedChangeListener((v,value)->{getSharedPreferences("deck",0).edit().putBoolean("eco",value).apply();CameraEngine e=engine();if(e!=null)e.setEco(value);});body.addView(eco);
        body.addView(text("ECO снижает нагрузку предпросмотра. Разрешение и FPS оригинала записи сохраняются. Экран можно выключить; остановка камеры доступна в уведомлении.",13,MUTED));
        body.addView(button("ПРОДОЛЖИТЬ В ФОНЕ",()->moveTaskToBack(true)));
        install=button("УСТАНОВИТЬ ОБНОВЛЕНИЕ",this::installUpdate);body.addView(install);
        if(BuildConfig.PREVIEW_BUILD){install.setVisibility(View.GONE);body.addView(text("Preview установлена рядом с основным приложением. Записи между ними не переносятся. Перед подключением выключите камеру в основном приложении.",13,MUTED));}
        body.addView(text("Не удаляйте приложение для обновления: при удалении стираются записи. Сначала скачайте оригиналы через Windows. Для звука нужны разрешение микрофона и повторный запуск камеры.",12,MUTED));
        dialog().setTitle("ПИТАНИЕ / СИСТЕМА").setView(scroll(body)).setPositiveButton("ЗАКРЫТЬ",null).show();update();
    }

    EditText number(LinearLayout body,String label,String value,boolean decimal){body.addView(text(label,11,MUTED));EditText edit=new EditText(this);edit.setSingleLine();edit.setTextColor(YELLOW);edit.setInputType(InputType.TYPE_CLASS_NUMBER|(decimal?InputType.TYPE_NUMBER_FLAG_DECIMAL:0));edit.setText(value);body.addView(edit);return edit;}
    void sensorSettings(){
        CameraEngine e=engine();if(e==null||!e.ready){toast("Сначала включите камеру");return;}
        try{
            JSONObject status=e.status();LinearLayout body=dialogBody();
            CheckBox automatic=check("Автоматическая экспозиция",status.optInt("manualIso")==0);automatic.setEnabled(status.optBoolean("manualSensor"));body.addView(automatic);
            TextView il=text("",14,YELLOW);body.addView(il);SeekBar iso=new SeekBar(this);int low=status.optInt("isoMin",50),high=Math.max(low,status.optInt("isoMax",low));iso.setMax(high-low);iso.setProgress(Math.max(0,(status.optInt("manualIso")>0?status.optInt("manualIso"):status.optInt("iso",low))-low));body.addView(iso);
            TextView el=text("",14,YELLOW);body.addView(el);SeekBar exposure=new SeekBar(this);exposure.setMax(1000);long emin=Math.max(1,status.optLong("exposureMinNs",1000)),emax=Math.max(emin,status.optLong("exposureMaxNs",100000000));long value=Math.max(emin,status.optLong("manualExposureNs")>0?status.optLong("manualExposureNs"):status.optLong("exposureNs",emin));double span=Math.log((double)emax/emin);exposure.setProgress(span==0?0:(int)(1000*Math.log((double)value/emin)/span));body.addView(exposure);
            CheckBox af=check("Автофокус",e.focus<0);body.addView(af);float max=(float)status.optDouble("focusMax",0);af.setEnabled(max>0);SeekBar focus=new SeekBar(this);focus.setMax(1000);focus.setProgress(max==0?0:(int)(1000*Math.max(0,e.focus)/max));body.addView(focus);
            Runnable labels=()->{il.setText("ISO  "+(low+iso.getProgress()));long ns=(long)(emin*Math.exp(span*exposure.getProgress()/1000.0));el.setText("Выдержка  "+shutterValue(ns));iso.setEnabled(!automatic.isChecked()&&automatic.isEnabled());exposure.setEnabled(iso.isEnabled());focus.setEnabled(!af.isChecked()&&max>0);};
            SeekBar.OnSeekBarChangeListener listener=new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){labels.run();}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}};iso.setOnSeekBarChangeListener(listener);exposure.setOnSeekBarChangeListener(listener);automatic.setOnCheckedChangeListener((v,c)->labels.run());af.setOnCheckedChangeListener((v,c)->labels.run());labels.run();
            dialog().setTitle("Экспозиция").setView(scroll(body)).setNegativeButton("Закрыть",null).setPositiveButton("Применить",(d,w)->command("settings","iso",automatic.isChecked()?0:low+iso.getProgress(),"exposureNs",automatic.isChecked()?0:(long)(emin*Math.exp(span*exposure.getProgress()/1000.0)),"focus",af.isChecked()?-1:max*focus.getProgress()/1000.0)).show();
        }catch(Exception ex){toast(ex.getMessage());}
    }

    ArrayAdapter<String> adapter(List<String> values){return new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,values){
        @Override public View getView(int p,View old,ViewGroup parent){TextView v=(TextView)super.getView(p,old,parent);v.setTextColor(YELLOW);v.setTextSize(14);v.setTypeface(Typeface.MONOSPACE);return v;}
        @Override public View getDropDownView(int p,View old,ViewGroup parent){TextView v=(TextView)super.getDropDownView(p,old,parent);v.setTextColor(YELLOW);v.setBackgroundColor(PANEL);v.setTextSize(14);return v;}
    };}
    Spinner spinner(LinearLayout body,String label,List<String> values){body.addView(text(label,11,MUTED));Spinner s=new Spinner(this);s.setAdapter(adapter(values));body.addView(s,new LinearLayout.LayoutParams(-1,dp(42)));return s;}
    static List<JSONObject> sizes(JSONArray array)throws Exception{List<JSONObject> result=new ArrayList<>();for(int i=0;i<array.length();i++)result.add(array.getJSONObject(i));return result;}
    static List<String> labels(List<JSONObject> values){List<String> result=new ArrayList<>();for(JSONObject item:values)result.add(item.optInt("width")+" × "+item.optInt("height"));return result;}
    interface Choice{void select(int index)throws Exception;}
    void choose(String title,List<String> items,int selected,Choice action){dialog().setTitle(title).setSingleChoiceItems(items.toArray(new String[0]),selected,(d,index)->{try{action.select(index);}catch(Exception ex){toast(ex.getMessage());}d.dismiss();}).setNegativeButton("Закрыть",null).show();}
    void captureSettings(){
        CameraEngine e=engine();if(e==null||!e.ready){toast("Сначала включите камеру");return;}if(e.recording||e.photoPending){toast("Сначала остановите запись");return;}
        try{
            LinearLayout body=dialogBody();JSONObject cap=e.capabilities;
            body.addView(text("Возможности этого объектива · без искусственного ограничения 4K / 24 Мп",12,MUTED));
            List<JSONObject> video=sizes(cap.getJSONArray("videoModes"));video.sort((a,b)->Long.compare((long)b.optInt("width")*b.optInt("height"),(long)a.optInt("width")*a.optInt("height")));
            body.addView(button("Видео   "+e.videoWidth+" × "+e.videoHeight+"   ›",()->choose("Разрешение видео",labels(video),-1,index->{JSONObject v=video.get(index);JSONArray rates=v.getJSONArray("fps");command("capture_config","videoWidth",v.getInt("width"),"videoHeight",v.getInt("height"),"videoFps",rates.getInt(rates.length()-1));})));
            List<String> rates=new ArrayList<>();for(JSONObject v:video)if(v.optInt("width")==e.videoWidth&&v.optInt("height")==e.videoHeight){JSONArray fps=v.getJSONArray("fps");for(int i=0;i<fps.length();i++)rates.add(fps.getString(i));}
            body.addView(button("Частота   "+e.videoFps+" fps   ›",()->choose("Частота видеозаписи",rates,rates.indexOf(Integer.toString(e.videoFps)),index->command("capture_config","videoFps",Integer.parseInt(rates.get(index))))));
            List<String> formats=new ArrayList<>();JSONArray fs=cap.getJSONArray("photoFormats");for(int i=0;i<fs.length();i++)formats.add(fs.getString(i));
            body.addView(button("Фото   "+e.photoFormat.toUpperCase(Locale.US)+"   ›",()->choose("Формат фото",formats,formats.indexOf(e.photoFormat),index->{JSONArray dims=cap.getJSONArray(formats.get(index).equals("dng")?"rawSizes":"photoSizes");JSONObject p=dims.getJSONObject(0);command("capture_config","photoFormat",formats.get(index),"photoWidth",p.getInt("width"),"photoHeight",p.getInt("height"));})));
            List<JSONObject> photo=sizes(cap.getJSONArray(e.photoFormat.equals("dng")?"rawSizes":"photoSizes"));photo.sort((a,b)->Long.compare((long)b.optInt("width")*b.optInt("height"),(long)a.optInt("width")*a.optInt("height")));
            body.addView(button("Размер фото   "+String.format(Locale.US,"%.1f Мп",e.photoWidth*e.photoHeight/1e6)+"   ›",()->choose("Размер фото",labels(photo),-1,index->{JSONObject p=photo.get(index);command("capture_config","photoWidth",p.getInt("width"),"photoHeight",p.getInt("height"));})));
            CheckBox sound=check("Звук в видеозаписи",e.sound);sound.setOnCheckedChangeListener((v,value)->command("capture_config","audio",value));body.addView(sound);
            body.addView(button("Все возможности телефона   ›",this::capabilityDetails));body.addView(button("Папка сохранения   ›",this::storageSettings));
            dialog().setTitle("Качество съёмки").setView(scroll(body)).setPositiveButton("Готово",null).show();
        }catch(Exception ex){toast(ex.getMessage());}
    }
    void capabilityDetails(){CameraEngine e=engine();if(e==null){toast("Сначала включите камеру");return;}try{LinearLayout body=dialogBody();JSONObject cap=e.capabilities;StringBuilder info=new StringBuilder("Обычная видеозапись\n");JSONArray modes=cap.getJSONArray("videoModes");for(int i=0;i<modes.length();i++){JSONObject m=modes.getJSONObject(i);info.append(m.optInt("width")).append(" × ").append(m.optInt("height")).append("  ").append(m.getJSONArray("fps")).append(" fps\n");}info.append("\nФото / RAW\n").append(cap.getJSONArray("photoSizes")).append("\n").append(cap.getJSONArray("rawSizes")).append("\n\nВысокоскоростные режимы (специальный сеанс)\n").append(cap.optJSONArray("highSpeedModes")).append("\n\nМаксимальное разрешение сенсора (специальный сеанс)\n").append(cap.optJSONArray("maximumSensorSizes")).append("\n\n").append(cap.optString("specialModesNote"));TextView details=text(info.toString(),13,WHITE);details.setTextIsSelectable(true);body.addView(details);dialog().setTitle("Возможности объектива").setView(scroll(body)).setPositiveButton("Готово",null).show();}catch(Exception ex){toast(ex.getMessage());}}
    void storageSettings(){
        CameraEngine e=engine();if(e!=null&&(e.recording||e.photoPending)){toast("Остановите запись перед сменой папки");return;}
        dialog().setTitle("Куда сохранять").setItems(new String[]{"Память телефона","Выбрать папку или подключённый USB-диск"},(d,index)->{if(index==0){getSharedPreferences("deck",0).edit().remove("captureTree").apply();nextStorageRefresh=0;}else{Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);startActivityForResult(intent,20);}}).setNegativeButton("Закрыть",null).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==20&&result==RESULT_OK&&data!=null&&data.getData()!=null){try{CameraEngine e=engine();if(e!=null&&(e.recording||e.photoPending)){toast("Запись продолжается: папка не изменена");return;}Uri tree=data.getData();int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);if(flags!=(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION))throw new java.io.IOException("Нужен доступ к чтению и записи");getContentResolver().takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);getSharedPreferences("deck",0).edit().putString("captureTree",tree.toString()).apply();nextStorageRefresh=0;toast("Папка сохранения выбрана");}catch(Exception ex){toast("Папка недоступна: "+ex.getMessage());}}}
    void attachPreview(){CameraEngine e=engine();if(e!=null&&nativeView.isAvailable()&&e!=attachedEngine){attachedEngine=e;attachedTexture=nativeView.getSurfaceTexture();e.attachLocal(attachedTexture);}}
    void transformPreview(){CameraEngine e=engine();if(e==null||!nativeView.isAvailable()||e.previewWidth==0)return;float w=nativeView.getWidth(),h=nativeView.getHeight();if(w==0||h==0)return;float bw=e.previewWidth,bh=e.previewHeight,rw=e.rotation%180==0?bw:bh,rh=e.rotation%180==0?bh:bw;float scale=Math.min(w/rw,h/rh);Matrix m=new Matrix();m.setScale(bw/w,bh/h);m.postTranslate(-bw/2,-bh/2);m.postRotate(e.rotation);boolean front=e.characteristics!=null&&Integer.valueOf(0).equals(e.characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING));m.postScale(front?-scale:scale,scale);m.postTranslate(w/2,h/2);nativeView.setTransform(m);}

    void toggle(){if(CameraService.active!=null){stopService(new Intent(this,CameraService.class));return;}if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO},10);return;}startCameraService();}
    void startCameraService(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},11);startForegroundService(new Intent(this,CameraService.class));}
    @Override public void onRequestPermissionsResult(int req,String[] permissions,int[] results){super.onRequestPermissionsResult(req,permissions,results);if(req==10){if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)startCameraService();else toast("Разрешите доступ к камере в настройках приложения");}}
    String shutterValue(long ns){if(ns<=0)return "—";double seconds=ns/1e9;return seconds<1?"1/"+Math.round(1/seconds):String.format(Locale.US,"%.1f\"",seconds);}
    void update(){
        CameraService s=CameraService.active;CameraEngine e=s==null?null:s.engine;boolean active=e!=null;
        start.setText(active?"Выключить":"Включить");buttonStyle(start,!active);
        if(install!=null){install.setEnabled(new File(getFilesDir(),"update.apk").isFile()&&(!active||(!e.recording&&!e.photoPending)));buttonStyle(install,false);}
        mode.setEnabled(active&&e.ready&&!e.recording&&!e.photoPending&&!commandPending);shutter.setEnabled(active&&e.ready&&!e.photoPending&&!commandPending);
        if(!active){preview.setVisibility(View.VISIBLE);storage.setText("Память телефона · "+android.text.format.Formatter.formatFileSize(this,new File(getExternalFilesDir(null),"captures").getUsableSpace())+" свободно");connection.setText("Включите камеру — изображение и PIN появятся здесь");state.setText("Камера выключена");telemetry.setText("ISO —     S —     AF");center.setText("Камера готова к работе\nНажмите «Включить» и разрешите доступ");center.setVisibility(View.VISIBLE);preview.setImageDrawable(null);sequence=-1;notice.setText(CameraService.lastStartupError.isEmpty()?"Оригиналы сохраняются на телефоне":CameraService.lastStartupError);if(code!=null)code.setText("Камера выключена");return;}
        if(System.currentTimeMillis()>s.pinExpires)s.rotatePin();
        connection.setText(s.tokens.isEmpty()?"Подключить к компьютеру  ·  PIN "+s.pin+"\n"+getSharedPreferences("deck",0).getString("name",Build.MODEL):"Подключено компьютеров: "+s.tokens.size()+"  ·  Настройки подключения");
        state.setText((e.captureMode.equals("video")?"MOVIE":"PHOTO")+"  /  "+(e.recording?"● REC":e.powerState.equals("standby")?"STBY":"LIVE")+"  /  "+s.tokens.size()+" LINK");state.setTextColor(e.recording?0xffff5a52:YELLOW);
        telemetry.setText("ISO "+(e.measuredIso>0?e.measuredIso:"—")+"    S "+shutterValue(e.measuredExposure)+"    "+(e.focus<0?"AF-C":"MF"));
        isoReadout.setText("ISO  "+(e.measuredIso>0?e.measuredIso:"—"));shutterReadout.setText("S  "+shutterValue(e.measuredExposure));formatReadout.setText((e.captureMode.equals("video")?e.videoHeight+"p · "+e.videoFps+" fps":e.photoFormat.toUpperCase(Locale.US))+" ▴");
        mode.setText(e.captureMode.equals("video")?"Видео":"Фото");shutter.setText(e.captureMode.equals("photo")?"◎":e.recording?"■":"●");buttonStyle(mode,false);buttonStyle(shutter,!e.recording);
        if(e.recording){shutter.setBackground(panel(0xffa62520,0xffed554b,2));shutter.setTextColor(WHITE);}
        center.setVisibility(showPreview&&(e.nativePreview||e.frame.length>0)?View.GONE:View.VISIBLE);center.setText(showPreview?"Запускаем камеру…":"Просмотр приостановлен\nВключите «Показывать камеру»");
        String error=e.error.isEmpty()?s.error:e.error;notice.setText(!error.isEmpty()?error:!s.updateMessage.isEmpty()?s.updateMessage:"REC "+e.videoWidth+"×"+e.videoHeight+" "+e.videoFps+"p · "+(e.sound?"AAC":"MUTE")+"  /  VIEW "+e.power.fps()+"p");
        if(SystemClock.elapsedRealtime()>nextStorageRefresh){nextStorageRefresh=SystemClock.elapsedRealtime()+3000;new Thread(()->{long free=e.store.freeBytes(),phone=e.mediaDir.getUsableSpace();String label=e.store.label();ui.post(()->storage.setText(label+" · "+(free>=0?android.text.format.Formatter.formatFileSize(this,free)+" свободно":"объём недоступен")+(e.store.tree()!=null?"  /  телефон: "+android.text.format.Formatter.formatFileSize(this,phone):"")));},"CameraDeck-storage-status").start();}
        if(pairing!=null&&pairing.isShowing()){code.setText("PIN  "+s.pin);fingerprint.setText("Wi-Fi: "+localAddresses()+"\n\nSHA-256\n"+s.fingerprint.replaceAll("(.{8})","$1 "));}
    }
    void renderPreview(){
        CameraEngine e=engine();if(e==null||!resumed||!showPreview)return;
        e.demandLocal();attachPreview();transformPreview();
        preview.setVisibility(e.nativePreview?View.GONE:View.VISIBLE);
        if(e.nativePreview)return;
        if(showPreview&&e.frameSequence!=sequence){sequence=e.frameSequence;byte[] bytes=e.frame;Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);if(bitmap!=null){Matrix matrix=new Matrix();matrix.postRotate(e.rotation);
            if(e.characteristics!=null&&Integer.valueOf(android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT).equals(e.characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)))matrix.postScale(-1,1);
            Bitmap next=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);preview.setImageBitmap(next);
            displayedFrame=next;if(bitmap!=next)bitmap.recycle();}}
    }
    String localAddresses(){List<String> ips=new ArrayList<>();try{for(NetworkInterface n:Collections.list(NetworkInterface.getNetworkInterfaces()))for(InetAddress a:Collections.list(n.getInetAddresses()))if(a instanceof Inet4Address&&a.isSiteLocalAddress())ips.add(a.getHostAddress());}catch(Exception ignored){}return String.join(", ",ips);}
    void installUpdate(){CameraEngine e=engine();if(e!=null&&(e.recording||e.photoPending))return;if(!getPackageManager().canRequestPackageInstalls()){startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));return;}stopService(new Intent(this,CameraService.class));Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://"+getPackageName()+".updates/update.apk"),"application/vnd.android.package-archive");intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(intent);}
    @Override protected void onResume(){super.onResume();resumed=true;orientation.enable();ui.post(refresh);ui.post(frames);}
    @Override protected void onPause(){resumed=false;CameraEngine e=engine();if(e!=null)e.hideLocal();ui.removeCallbacks(refresh);ui.removeCallbacks(frames);orientation.disable();super.onPause();}
}
