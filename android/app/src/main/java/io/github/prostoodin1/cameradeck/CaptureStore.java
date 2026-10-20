package io.github.prostoodin1.cameradeck;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.system.*;
import org.json.*;
import java.io.*;
import java.util.*;

/** User-selected SAF tree (including USB) or private phone storage. Never silently changes destination. */
final class CaptureStore {
    final Context context;
    final File internal;
    CaptureStore(Context context,File internal){this.context=context;this.internal=internal;}
    String selection(){return context.getSharedPreferences("deck",0).getString("captureTree","");}
    Uri tree(){String value=selection();return value.isEmpty()?null:Uri.parse(value);}
    Uri root(){Uri t=tree();return t==null?null:DocumentsContract.buildDocumentUriUsingTree(t,DocumentsContract.getTreeDocumentId(t));}
    String label(){
        if(tree()==null)return "Память телефона";
        try(Cursor c=context.getContentResolver().query(root(),new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
            if(c!=null&&c.moveToFirst())return c.getString(0);
        }catch(Exception ignored){}
        return "Выбранная папка недоступна";
    }
    long freeBytes(){
        if(tree()==null)return internal.getUsableSpace();
        if(android.os.Build.VERSION.SDK_INT>=30&&"com.android.externalstorage.documents".equals(tree().getAuthority())){
            String volumeId=DocumentsContract.getTreeDocumentId(tree()).split(":",2)[0];
            android.os.storage.StorageManager manager=context.getSystemService(android.os.storage.StorageManager.class);
            for(android.os.storage.StorageVolume volume:manager.getStorageVolumes())if(volume.isPrimary()&&volumeId.equals("primary")||volume.getUuid()!=null&&volume.getUuid().equalsIgnoreCase(volumeId)){
                File directory=volume.getDirectory();if(directory!=null)return directory.getUsableSpace();
            }
        }
        // SAF providers may be cloud-backed or may not expose capacity. Unknown is not zero.
        try(ParcelFileDescriptor fd=context.getContentResolver().openFileDescriptor(root(),"r")){
            StructStatVfs stat=Os.fstatvfs(fd.getFileDescriptor());return stat.f_bavail*stat.f_frsize;
        }catch(Exception ignored){return -1;}
    }
    final class Target implements AutoCloseable {
        final String name; final File file; Uri document; ParcelFileDescriptor descriptor;
        Target(String name)throws Exception{
            this.name=name;
            if(tree()==null){file=new File(internal,name+".partial");descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_WRITE|ParcelFileDescriptor.MODE_CREATE|ParcelFileDescriptor.MODE_TRUNCATE);}
            else{
                file=null;document=DocumentsContract.createDocument(context.getContentResolver(),root(),"video/mp4",name+".partial");
                if(document==null)throw new IOException("Не удалось создать файл в выбранной папке");
                descriptor=context.getContentResolver().openFileDescriptor(document,"rw");
            }
            if(descriptor==null)throw new IOException("Накопитель недоступен");
            try{Os.lseek(descriptor.getFileDescriptor(),0,OsConstants.SEEK_CUR);}catch(Exception e){close();throw new IOException("Для MP4 нужен накопитель с произвольным доступом. Выберите локальную папку или USB-диск.",e);}
        }
        FileDescriptor fd(){return descriptor.getFileDescriptor();}
        void finish()throws Exception{
            close();
            if(file!=null){if(!file.renameTo(new File(internal,name)))throw new IOException("Не удалось завершить MP4");}
            else if(DocumentsContract.renameDocument(context.getContentResolver(),document,name)==null)throw new IOException("Накопитель не подтвердил завершение MP4; сохранён partial");
        }
        public void close()throws IOException{if(descriptor!=null){descriptor.close();descriptor=null;}}
    }
    Target video(String name)throws Exception{return new Target(name);}
    void photo(File file)throws Exception{
        if(tree()==null)return;
        Uri doc=DocumentsContract.createDocument(context.getContentResolver(),root(),"application/octet-stream",file.getName()+".partial");
        if(doc==null)throw new IOException("Папка сохранения недоступна; фото осталось в памяти телефона");
        try(InputStream in=new FileInputStream(file);OutputStream out=context.getContentResolver().openOutputStream(doc,"w")){
            if(out==null)throw new IOException("Накопитель недоступен; фото осталось в памяти телефона");
            byte[] buffer=new byte[256*1024];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
        }
        if(DocumentsContract.renameDocument(context.getContentResolver(),doc,file.getName())==null)throw new IOException("Не удалось завершить копирование; оригинал остался на телефоне");
        if(!file.delete())throw new IOException("Фото сохранено в папке, но временный оригинал остался на телефоне");
    }
    static boolean valid(String name){return name.matches("CD_[0-9]+_[a-f0-9]{8}\\.(jpg|png|webp|dng|mp4)");}
    JSONArray files()throws Exception{
        List<JSONObject> items=new ArrayList<>();
        File[] local=internal.listFiles();if(local!=null)for(File f:local)if(f.isFile()&&listed(f.getName()))items.add(new JSONObject().put("name",f.getName()).put("size",f.length()).put("modified",f.lastModified()));
        if(tree()!=null){Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree(),DocumentsContract.getTreeDocumentId(tree()));
            try(Cursor c=context.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED},null,null,null)){
                if(c!=null)while(c.moveToNext())if(listed(c.getString(0)))items.add(new JSONObject().put("name",c.getString(0)).put("size",c.getLong(1)).put("modified",c.getLong(2)));
            }
        }
        items.sort((a,b)->Long.compare(b.optLong("modified"),a.optLong("modified")));JSONArray result=new JSONArray();for(JSONObject item:items){if(result.length()==100)break;item.put("complete",!item.optString("name").endsWith(".partial"));result.put(item);}return result;
    }
    static boolean listed(String name){return valid(name)||name.endsWith(".partial")&&valid(name.substring(0,name.length()-8));}
    ParcelFileDescriptor descriptor(String name)throws Exception{
        if(!valid(name))throw new IOException("Недопустимое имя файла");
        File file=new File(internal,name);if(file.isFile())return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
        if(tree()!=null){Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree(),DocumentsContract.getTreeDocumentId(tree()));
            try(Cursor c=context.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
                if(c!=null)while(c.moveToNext())if(name.equals(c.getString(1))){ParcelFileDescriptor fd=context.getContentResolver().openFileDescriptor(DocumentsContract.buildDocumentUriUsingTree(tree(),c.getString(0))),"r");if(fd!=null)return fd;}
            }
        }throw new FileNotFoundException("Материал или накопитель недоступен");
    }
    InputStream open(String name)throws Exception{
        if(!valid(name))throw new IOException("Недопустимое имя файла");
        File f=new File(internal,name);if(f.isFile())return new FileInputStream(f);
        if(tree()!=null){Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree(),DocumentsContract.getTreeDocumentId(tree()));
            try(Cursor c=context.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
                if(c!=null)while(c.moveToNext())if(name.equals(c.getString(1))){InputStream in=context.getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree(),c.getString(0)));if(in!=null)return in;}
            }
        }
        throw new FileNotFoundException("Файл или накопитель недоступен");
    }
    long size(String name)throws Exception{JSONArray list=files();for(int i=0;i<list.length();i++){JSONObject f=list.getJSONObject(i);if(name.equals(f.getString("name")))return f.getLong("size");}throw new FileNotFoundException(name);}
}
