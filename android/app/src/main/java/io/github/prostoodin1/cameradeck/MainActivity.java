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
    static final int BLACK=0xff0b1019, PANEL=0xff172334, YELLOW=0xff8cbdff, WHITE=0xffedf2fa, MUTED=0xff99abc3;
    final Handler ui=new Handler(Looper.getMainLooper());
    TextView state,telemetry,notice,center,code,fingerprint;
    ImageView preview;
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
    void buttonStyle(Button b,boolean primary){b.setTextColor(primary?BLACK:YELLOW);b.setBackground(panel(primary?YELLOW:PANEL,primary?YELLOW:0xff31445f,14));b.setAlpha(b.isEnabled()?1f:0.4f);}
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
        LinearLayout root=column();root.setBackgroundColor(BLACK);root.setPadding(dp(9),dp(6),dp(9),dp(6));setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            int top,bottom,left,right;
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());top=bars.top;bottom=bars.bottom;left=bars.left;right=bars.right;}
            else{top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();left=insets.getSystemWindowInsetLeft();right=insets.getSystemWindowInsetRight();}
            v.setPadding(dp(9)+left,dp(6)+top,dp(9)+right,dp(6)+bottom);return insets;
        });
        LinearLayout header=row();LinearLayout brand=column();
        TextView logo=text(BuildConfig.PREVIEW_BUILD?"Camera Deck Preview":"Camera Deck",24,WHITE);logo.setTypeface(null,Typeface.BOLD);brand.addView(logo);
        TextView sub=text("Камера телефона · управление с компьютера",11,MUTED);brand.addView(sub);
        header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));start=button("Включить",this::toggle);header.addView(start,new LinearLayout.LayoutParams(dp(104),dp(48)));root.addView(header);
        connection=text("Включите камеру — изображение и PIN появятся здесь",12,MUTED);
        connection.setBackground(panel(PANEL,0xff31445f,16));connection.setPadding(dp(14),dp(12),dp(14),dp(12));
        connection.setOnClickListener(v->pairingSettings());root.addView(connection,new LinearLayout.LayoutParams(-1,-2));
        FrameLayout finder=new FrameLayout(this);finder.setBackground(panel(0xff070c13,0xff29394f,22));finder.setClipToOutline(true);
        LinearLayout.LayoutParams finderParams=new LinearLayout.LayoutParams(-1,0,1);finderParams.setMargins(0,dp(8),0,dp(5));root.addView(finder,finderParams);
        preview=new ImageView(this);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);finder.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        center=text("Камера готова к работе\nНажмите «Включить» и разрешите доступ",14,MUTED);center.setGravity(Gravity.CENTER);finder.addView(center,new FrameLayout.LayoutParams(-1,-1));
        state=text("PHOTO  /  STBY",12,YELLOW);state.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);state.setBackground(panel(0xb3182539,0x33485e7a,12));finder.addView(state,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        telemetry=text("ISO —     S —     AF",14,YELLOW);telemetry.setTypeface(Typeface.MONOSPACE);telemetry.setBackground(panel(0xb3182539,0x33485e7a,12));finder.addView(telemetry,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        LinearLayout readouts=row();isoReadout=button("ISO  —",this::sensorSettings);shutterReadout=button("S  —",this::sensorSettings);formatReadout=button("JPEG",this::captureSettings);
        cell(readouts,isoReadout,48);cell(readouts,shutterReadout,48);cell(readouts,formatReadout,48);root.addView(readouts);
        notice=text("Оригиналы сохраняются на телефоне",10,MUTED);notice.setMaxLines(2);root.addView(notice);
        LinearLayout controls=row();mode=button("Фото",()->{CameraEngine e=engine();if(e!=null)command("capture_config","mode",e.captureMode.equals("photo")?"video":"photo");});
        shutter=button("◎ ФОТО",()->{CameraEngine e=engine();if(e!=null)command(e.captureMode.equals("photo")?"photo":e.recording?"record_stop":"record_start");});
        Button background=button("Объектив",this::lensSettings);cell(controls,mode,52);cell(controls,shutter,52);cell(controls,background,52);root.addView(controls);
        LinearLayout menus=row();cell(menus,button("Настройки",this::captureSettings),48);cell(menus,button("Подключить",this::pairingSettings),48);cell(menus,button("Ещё",this::powerSettings),48);root.addView(menus);
        LinearLayout switches=row();localPreview=check("Показывать камеру",true);localPreview.setOnCheckedChangeListener((v,value)->{showPreview=value;CameraEngine e=engine();if(!value&&e!=null)e.hideLocal();if(!value)preview.setImageDrawable(null);sequence=-1;});
        switches.addView(localPreview);TextView version=text("v"+BuildConfig.VERSION_NAME+"  ·  Wi-Fi",10,MUTED);version.setGravity(Gravity.END);switches.addView(version,new LinearLayout.LayoutParams(0,-2,1));root.addView(switches);
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
                    w.setBackgroundDrawable(panel(blur?0xdc172334:PANEL,0xff31445f,24));
                    if(Build.VERSION.SDK_INT>=31&&blur){w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);w.setBackgroundBlurRadius(dp(24));WindowManager.LayoutParams a=w.getAttributes();a.setBlurBehindRadius(dp(20));w.setAttributes(a);}
                    w.setDimAmount(0.5f);
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
        LinearLayout body=dialogBody();eco=check("ECO · предпросмотр до 5 кадров/с",getSharedPreferences("deck",0).getBoolean("eco",true));
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
            JSONObject s=e.status();LinearLayout body=dialogBody();
            CheckBox auto=check("AUTO EXPOSURE",s.optInt("manualIso")==0);auto.setEnabled(s.optBoolean("manualSensor"));body.addView(auto);
            EditText iso=number(body,"ISO  "+s.optInt("isoMin")+"–"+s.optInt("isoMax"),Integer.toString(s.optInt("manualIso")>0?s.optInt("manualIso"):Math.max(100,s.optInt("iso"))),false);
            EditText exposure=number(body,"ВЫДЕРЖКА · мс",String.format(Locale.US,"%.3f",(s.optLong("manualExposureNs")>0?s.optLong("manualExposureNs"):Math.max(1000000,s.optLong("exposureNs")))/1e6),true);
            Runnable enable=()->{iso.setEnabled(!auto.isChecked()&&s.optBoolean("manualSensor"));exposure.setEnabled(iso.isEnabled());};auto.setOnCheckedChangeListener((v,value)->enable.run());enable.run();
            CheckBox af=check("AF-C · автофокус",s.optDouble("focus",-1)<0);af.setEnabled(s.optDouble("focusMax")>0);body.addView(af);
            EditText focus=number(body,"MF · диоптрии (0 = бесконечность)",String.format(Locale.US,"%.2f",Math.max(0,s.optDouble("focus",-1))),true);focus.setEnabled(!af.isChecked()&&s.optDouble("focusMax")>0);af.setOnCheckedChangeListener((v,value)->focus.setEnabled(!value&&s.optDouble("focusMax")>0));
            dialog().setTitle("ЭКСПОЗИЦИЯ / ФОКУС").setView(scroll(body)).setNegativeButton("ОТМЕНА",null).setPositiveButton("ПРИМЕНИТЬ",(d,w)->{try{command("settings","iso",auto.isChecked()?0:Integer.parseInt(iso.getText().toString()),"exposureNs",auto.isChecked()?0:Math.round(Double.parseDouble(exposure.getText().toString())*1e6),"focus",af.isChecked()?-1:Double.parseDouble(focus.getText().toString()));}catch(Exception ex){toast("Проверьте числовые значения");}}).show();
        }catch(Exception ex){toast(ex.getMessage());}
    }

    ArrayAdapter<String> adapter(List<String> values){return new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,values){
        @Override public View getView(int p,View old,ViewGroup parent){TextView v=(TextView)super.getView(p,old,parent);v.setTextColor(YELLOW);v.setTextSize(14);v.setTypeface(Typeface.MONOSPACE);return v;}
        @Override public View getDropDownView(int p,View old,ViewGroup parent){TextView v=(TextView)super.getDropDownView(p,old,parent);v.setTextColor(YELLOW);v.setBackgroundColor(PANEL);v.setTextSize(14);return v;}
    };}
    Spinner spinner(LinearLayout body,String label,List<String> values){body.addView(text(label,11,MUTED));Spinner s=new Spinner(this);s.setAdapter(adapter(values));body.addView(s,new LinearLayout.LayoutParams(-1,dp(42)));return s;}
    static List<JSONObject> sizes(JSONArray array)throws Exception{List<JSONObject> result=new ArrayList<>();for(int i=0;i<array.length();i++)result.add(array.getJSONObject(i));return result;}
    static List<String> labels(List<JSONObject> values){List<String> result=new ArrayList<>();for(JSONObject item:values)result.add(item.optInt("width")+" × "+item.optInt("height"));return result;}
    void captureSettings(){
        CameraEngine e=engine();if(e==null||!e.ready){toast("Сначала включите камеру");return;}if(e.recording||e.photoPending){toast("Остановите запись и дождитесь сохранения фото");return;}
        try{
            LinearLayout body=dialogBody();JSONObject cap=e.capabilities;
            List<JSONObject> modes=sizes(cap.getJSONArray("videoModes"));Spinner video=spinner(body,"ВИДЕО · РАЗРЕШЕНИЕ",labels(modes));
            Spinner fps=spinner(body,"ВИДЕО · ЦЕЛЕВОЙ FPS",Collections.singletonList(Integer.toString(e.videoFps)));
            Runnable rates=()->{List<String> values=new ArrayList<>();if(!modes.isEmpty()){JSONArray array=modes.get(video.getSelectedItemPosition()).optJSONArray("fps");if(array!=null)for(int i=0;i<array.length();i++)values.add(Integer.toString(array.optInt(i)));}fps.setAdapter(adapter(values));for(int i=0;i<values.size();i++)if(values.get(i).equals(Integer.toString(e.videoFps)))fps.setSelection(i);};
            video.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){rates.run();}});
            for(int i=0;i<modes.size();i++)if(modes.get(i).optInt("width")==e.videoWidth&&modes.get(i).optInt("height")==e.videoHeight)video.setSelection(i);rates.run();
            CheckBox sound=check("AAC · звук в MP4",e.sound);body.addView(sound);
            List<String> formats=new ArrayList<>();JSONArray f=cap.getJSONArray("photoFormats");for(int i=0;i<f.length();i++)formats.add(f.getString(i));
            Spinner format=spinner(body,"ФОТО · ФОРМАТ",formats);format.setSelection(Math.max(0,formats.indexOf(e.photoFormat)));
            Spinner photo=spinner(body,"ФОТО · РАЗРЕШЕНИЕ",Collections.singletonList("—"));List<JSONObject> photoModes=new ArrayList<>();
            Runnable photoSizes=()->{try{photoModes.clear();photoModes.addAll(sizes(cap.getJSONArray(formats.get(format.getSelectedItemPosition()).equals("dng")?"rawSizes":"photoSizes")));photo.setAdapter(adapter(labels(photoModes)));for(int i=0;i<photoModes.size();i++)if(photoModes.get(i).optInt("width")==e.photoWidth&&photoModes.get(i).optInt("height")==e.photoHeight)photo.setSelection(i);}catch(Exception ex){toast(ex.getMessage());}};
            format.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){photoSizes.run();}});photoSizes.run();
            body.addView(text("PNG / WebP — обработанный JPEG. DNG — RAW при поддержке камеры. Просмотр: до 5/10 кадров/с; выбранный FPS относится к записи.",11,MUTED));
            dialog().setTitle("СЪЁМКА / ФОРМАТЫ").setView(scroll(body)).setNegativeButton("ОТМЕНА",null).setPositiveButton("ПРИМЕНИТЬ",(d,w)->{try{JSONObject v=modes.isEmpty()?new JSONObject().put("width",e.videoWidth).put("height",e.videoHeight):modes.get(video.getSelectedItemPosition());JSONObject p=photoModes.get(photo.getSelectedItemPosition());command("capture_config","videoWidth",v.getInt("width"),"videoHeight",v.getInt("height"),"videoFps",fps.getSelectedItem()==null?e.videoFps:Integer.parseInt(fps.getSelectedItem().toString()),"photoFormat",formats.get(format.getSelectedItemPosition()),"photoWidth",p.getInt("width"),"photoHeight",p.getInt("height"),"audio",sound.isChecked());}catch(Exception ex){toast("Недоступная комбинация настроек");}}).show();
        }catch(Exception ex){toast(ex.getMessage());}
    }

    void toggle(){if(CameraService.active!=null){stopService(new Intent(this,CameraService.class));return;}if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO},10);return;}startCameraService();}
    void startCameraService(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},11);startForegroundService(new Intent(this,CameraService.class));}
    @Override public void onRequestPermissionsResult(int req,String[] permissions,int[] results){super.onRequestPermissionsResult(req,permissions,results);if(req==10){if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)startCameraService();else toast("Разрешите доступ к камере в настройках приложения");}}
    String shutterValue(long ns){if(ns<=0)return "—";double seconds=ns/1e9;return seconds<1?"1/"+Math.round(1/seconds):String.format(Locale.US,"%.1f\"",seconds);}
    void update(){
        CameraService s=CameraService.active;CameraEngine e=s==null?null:s.engine;boolean active=e!=null;
        start.setText(active?"Выключить":"Включить");buttonStyle(start,!active);
        if(install!=null){install.setEnabled(new File(getFilesDir(),"update.apk").isFile()&&(!active||(!e.recording&&!e.photoPending)));buttonStyle(install,false);}
        mode.setEnabled(active&&e.ready&&!e.recording&&!e.photoPending&&!commandPending);shutter.setEnabled(active&&e.ready&&!e.photoPending&&!commandPending);
        if(!active){connection.setText("Включите камеру — изображение и PIN появятся здесь");state.setText("Камера выключена");telemetry.setText("ISO —     S —     AF");center.setText("Камера готова к работе\nНажмите «Включить» и разрешите доступ");center.setVisibility(View.VISIBLE);preview.setImageDrawable(null);sequence=-1;notice.setText(CameraService.lastStartupError.isEmpty()?"Оригиналы сохраняются на телефоне":CameraService.lastStartupError);if(code!=null)code.setText("Камера выключена");return;}
        if(System.currentTimeMillis()>s.pinExpires)s.rotatePin();
        connection.setText(s.tokens.isEmpty()?"Подключить к компьютеру  ·  PIN "+s.pin+"\n"+getSharedPreferences("deck",0).getString("name",Build.MODEL):"Подключено компьютеров: "+s.tokens.size()+"  ·  Настройки подключения");
        state.setText((e.captureMode.equals("video")?"MOVIE":"PHOTO")+"  /  "+(e.recording?"● REC":e.powerState.equals("standby")?"STBY":"LIVE")+"  /  "+s.tokens.size()+" LINK");state.setTextColor(e.recording?0xffff5a52:YELLOW);
        telemetry.setText("ISO "+(e.measuredIso>0?e.measuredIso:"—")+"    S "+shutterValue(e.measuredExposure)+"    "+(e.focus<0?"AF-C":"MF"));
        isoReadout.setText("ISO  "+(e.measuredIso>0?e.measuredIso:"—"));shutterReadout.setText("S  "+shutterValue(e.measuredExposure));formatReadout.setText(e.captureMode.equals("video")?e.videoHeight+"p "+e.videoFps:e.photoFormat.toUpperCase(Locale.US));
        mode.setText(e.captureMode.equals("video")?"Видео":"Фото");shutter.setText(e.captureMode.equals("photo")?"◎ ФОТО":e.recording?"■ Стоп":"● Запись");buttonStyle(mode,false);buttonStyle(shutter,!e.recording);
        if(e.recording){shutter.setBackground(panel(0xffa62520,0xffed554b,2));shutter.setTextColor(WHITE);}
        center.setVisibility(showPreview&&e.frame.length>0?View.GONE:View.VISIBLE);center.setText(showPreview?"Запускаем камеру…":"Просмотр приостановлен\nВключите «Показывать камеру»");
        String error=e.error.isEmpty()?s.error:e.error;notice.setText(!error.isEmpty()?error:!s.updateMessage.isEmpty()?s.updateMessage:"REC "+e.videoWidth+"×"+e.videoHeight+" "+e.videoFps+"p · "+(e.sound?"AAC":"MUTE")+"  /  VIEW "+e.power.fps()+"p");
        if(pairing!=null&&pairing.isShowing()){code.setText("PIN  "+s.pin);fingerprint.setText("Wi-Fi: "+localAddresses()+"\n\nSHA-256\n"+s.fingerprint.replaceAll("(.{8})","$1 "));}
    }
    void renderPreview(){
        CameraEngine e=engine();if(e==null||!resumed||!showPreview)return;
        e.demandLocal();
        if(showPreview&&e.frameSequence!=sequence){sequence=e.frameSequence;byte[] bytes=e.frame;Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);if(bitmap!=null){Matrix matrix=new Matrix();matrix.postRotate(e.rotation);
            if(e.characteristics!=null&&Integer.valueOf(android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT).equals(e.characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)))matrix.postScale(-1,1);
            Bitmap next=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);preview.setImageBitmap(next);
            if(displayedFrame!=null&&displayedFrame!=next)displayedFrame.recycle();displayedFrame=next;if(bitmap!=next)bitmap.recycle();}}
    }
    String localAddresses(){List<String> ips=new ArrayList<>();try{for(NetworkInterface n:Collections.list(NetworkInterface.getNetworkInterfaces()))for(InetAddress a:Collections.list(n.getInetAddresses()))if(a instanceof Inet4Address&&a.isSiteLocalAddress())ips.add(a.getHostAddress());}catch(Exception ignored){}return String.join(", ",ips);}
    void installUpdate(){CameraEngine e=engine();if(e!=null&&(e.recording||e.photoPending))return;if(!getPackageManager().canRequestPackageInstalls()){startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));return;}stopService(new Intent(this,CameraService.class));Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://"+getPackageName()+".updates/update.apk"),"application/vnd.android.package-archive");intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(intent);}
    @Override protected void onResume(){super.onResume();resumed=true;orientation.enable();ui.post(refresh);ui.post(frames);}
    @Override protected void onPause(){resumed=false;CameraEngine e=engine();if(e!=null)e.hideLocal();ui.removeCallbacks(refresh);ui.removeCallbacks(frames);orientation.disable();super.onPause();}
}
