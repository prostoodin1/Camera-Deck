package io.github.prostoodin1.cameradeck;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** One bounded sensor sheet. Dragging is local; a command is sent only on Apply. */
final class SensorSheet {
    static String value(String kind,double n){if(kind.equals("shutter"))return n<1e9?"1/"+Math.round(1e9/n):String.format(Locale.US,"%.1f\"",n/1e9);if(kind.equals("iso"))return Long.toString(Math.round(n));if(kind.equals("focus"))return n==0?"∞":String.format(Locale.US,"%.2f",n);return String.format(Locale.US,"%+.2f",n);}
    static void show(MainActivity a,String kind){
        CameraEngine engine=a.engine();if(engine==null||!engine.ready){a.toast("Сначала включите камеру");return;}
        try{
            JSONObject snapshot=engine.status();String context=SensorScale.context(snapshot);double[] bounds=SensorScale.bounds(kind,snapshot);double low=bounds[0],high=Math.max(low,bounds[1]);boolean supported=SensorScale.supported(kind,snapshot),ev=kind.equals("ev");
            String title=kind.equals("iso")?"Чувствительность":kind.equals("shutter")?"Выдержка":kind.equals("focus")?"Фокусировка":"Коррекция экспозиции";
            if(a.sensorDialog!=null)a.sensorDialog.dismiss();Dialog dialog=new Dialog(a);a.sensorDialog=dialog;dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);dialog.setOnDismissListener(d->{if(a.sensorDialog==dialog)a.sensorDialog=null;});
            LinearLayout outer=a.column();outer.setPadding(a.dp(20),a.dp(12),a.dp(20),a.dp(16));LinearLayout header=a.row();TextView name=a.text(kind.toUpperCase(Locale.US)+"  /  "+title,13,CameraDesign.MUTED);header.addView(name,new LinearLayout.LayoutParams(0,-2,1));Button close=a.button("×",dialog::dismiss);close.setContentDescription("Закрыть настройку");header.addView(close,new LinearLayout.LayoutParams(a.dp(42),a.dp(42)));outer.addView(header);
            LinearLayout body=a.column();ScrollView scroll=a.scroll(body);outer.addView(scroll,new LinearLayout.LayoutParams(-1,-2,1));
            TextView hero=a.text("",44,CameraDesign.ACCENT);hero.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));hero.setGravity(Gravity.CENTER);hero.setPadding(0,a.dp(12),0,a.dp(16));body.addView(hero);
            Switch automatic=new Switch(a);automatic.setShowText(false);automatic.setText(kind.equals("focus")?"AF · автофокус":"AUTO · ISO и выдержка");automatic.setTextColor(CameraDesign.TEXT);automatic.setTextSize(14);automatic.setChecked(kind.equals("focus")?snapshot.optDouble("focus",-1)<0:snapshot.optInt("manualIso")==0);automatic.setEnabled(supported);automatic.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{CameraDesign.ACCENT,0xffb8c2ce}));automatic.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{0xff716746,0xff414d5c}));automatic.setPadding(a.dp(6),a.dp(8),a.dp(6),a.dp(8));if(!ev)body.addView(automatic,new LinearLayout.LayoutParams(-1,a.dp(48)));
            double chosen=kind.equals("iso")?(snapshot.optInt("manualIso")>0?snapshot.optInt("manualIso"):snapshot.optInt("iso",(int)low)):kind.equals("shutter")?(snapshot.optLong("manualExposureNs")>0?snapshot.optLong("manualExposureNs"):snapshot.optLong("exposureNs",(long)low)):kind.equals("focus")?Math.max(0,snapshot.optDouble("focus",-1)):snapshot.optJSONObject("imageControls")==null?0:snapshot.getJSONObject("imageControls").optDouble("ev",0);
            final double[] exact={SensorScale.clamp(chosen,low,high)};
            SeekBar slider=new CameraDesign.Ruler(a);slider.setMax(10000);slider.setProgress(SensorScale.progress(kind,chosen,low,high));slider.setProgressTintList(ColorStateList.valueOf(CameraDesign.ACCENT));slider.setThumbTintList(ColorStateList.valueOf(CameraDesign.ACCENT));slider.setContentDescription(title);body.addView(slider,new LinearLayout.LayoutParams(-1,a.dp(50)));
            LinearLayout labels=a.row();TextView left=a.text(kind.equals("focus")?"∞ · дальше":value(kind,low),11,CameraDesign.MUTED),right=a.text(kind.equals("focus")?"Ближе":value(kind,high),11,CameraDesign.MUTED);labels.addView(left,new LinearLayout.LayoutParams(0,-2,1));labels.addView(right);body.addView(labels);
            GridLayout stops=new GridLayout(a);stops.setColumnCount(4);List<Double> values=SensorScale.presets(kind,snapshot);List<Button> chips=new ArrayList<>();body.addView(stops);
            Runnable refresh=()->{boolean auto=!ev&&automatic.isChecked();double number=Double.isFinite(exact[0])?exact[0]:SensorScale.fromProgress(kind,slider.getProgress(),low,high);hero.setText(auto?(kind.equals("focus")?"AF":"AUTO"):value(kind,number));slider.setEnabled(supported&&!auto);for(int i=0;i<chips.size();i++){double stop=values.get(i);Button b=chips.get(i);b.setEnabled(supported);a.buttonStyle(b,!auto&&Math.abs(number-stop)<Math.max(.01,Math.abs(stop)*.015));}if(android.os.Build.VERSION.SDK_INT>=30)slider.setStateDescription(hero.getText());};
            for(int i=0;i<values.size();i++){final double stop=values.get(i);Button chip=a.button(value(kind,stop),()->{automatic.setChecked(false);exact[0]=stop;slider.setProgress(SensorScale.progress(kind,stop,low,high));refresh.run();});chips.add(chip);GridLayout.LayoutParams p=new GridLayout.LayoutParams(GridLayout.spec(i/4),GridLayout.spec(i%4,1f));p.width=0;p.height=a.dp(44);p.setMargins(a.dp(3),a.dp(4),a.dp(3),a.dp(4));stops.addView(chip,p);}
            TextView note=a.text(!supported?"Настройка недоступна для текущего объектива / режима.":kind.equals("focus")?"Шкала объектива в диоптриях. Это не измерение расстояния.":ev?"Яркость по реальному шагу автоэкспозиции камеры.":"При открытии: ISO "+(snapshot.optInt("iso")>0?snapshot.optInt("iso"):"—")+" · "+a.shutterValue(snapshot.optLong("exposureNs")),12,CameraDesign.MUTED);body.addView(note);
            if(ev&&snapshot.optInt("manualIso")>0)note.setText("EV работает в AUTO. Для ручной яркости используйте ISO и выдержку.");
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){if(user)exact[0]=Double.NaN;refresh.run();}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});automatic.setOnCheckedChangeListener((v,on)->refresh.run());refresh.run();
            LinearLayout footer=a.row();a.cell(footer,a.button("Отмена",dialog::dismiss),48);Button apply=a.button("Применить",()->{
                try{CameraEngine current=a.engine();if(current!=engine||current==null||!current.ready||current.busy||!context.equals(SensorScale.context(current.status()))){a.toast("Объектив или режим изменился. Откройте настройку заново.");dialog.dismiss();return;}
                    JSONObject cmd=SensorScale.command(kind,automatic.isChecked(),Double.isFinite(exact[0])?exact[0]:SensorScale.fromProgress(kind,slider.getProgress(),low,high),current.status());List<Object> args=new ArrayList<>();for(Iterator<String> it=cmd.keys();it.hasNext();){String key=it.next();if(!key.equals("action")){args.add(key);args.add(cmd.get(key));}}a.command(cmd.getString("action"),args.toArray());dialog.dismiss();
                }catch(Exception error){a.toast(String.valueOf(error.getMessage()));}
            });a.buttonStyle(apply,true);apply.setEnabled(supported);a.cell(footer,apply,48);outer.addView(footer);dialog.setContentView(outer);
            Window window=dialog.getWindow();if(window!=null){window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);window.setGravity(Gravity.BOTTOM);CameraDesign.sheet(window,a.glassEnabled());}
            dialog.show();if(window!=null){int width=Math.min(a.getResources().getDisplayMetrics().widthPixels-a.dp(16),a.dp(500));outer.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));int height=Math.min(outer.getMeasuredHeight(),(int)(a.getResources().getDisplayMetrics().heightPixels*.84));window.setLayout(width,height);}
        }catch(Exception error){a.toast(String.valueOf(error.getMessage()));}
    }
}
