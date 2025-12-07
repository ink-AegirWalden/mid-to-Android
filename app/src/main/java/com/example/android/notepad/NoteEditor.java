/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.notepad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;


import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 支持笔记编辑 + 附件管理（图片/音频/视频） + 修改时间戳显示
 */
public class NoteEditor extends Activity {
    // 日志TAG
    private static final String TAG = "NoteEditor";

    // 查询投影：新增修改时间和分类字段
    private static final String[] PROJECTION =
            new String[]{
                    NotePad.Notes._ID,
                    NotePad.Notes.COLUMN_NAME_TITLE,
                    NotePad.Notes.COLUMN_NAME_NOTE,
                    NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, // 新增：修改时间
                    NotePad.Notes.COLUMN_NAME_CATEGORY // 新增：分类
            };

    // 保存状态常量
    private static final String ORIGINAL_CONTENT = "origContent";
    private static final String ORIGINAL_TITLE = "origTitle"; // 新增：原始标题
    private static final String ORIGINAL_CATEGORY = "origCategory"; // 新增：原始分类

    // Activity状态
    private static final int STATE_EDIT = 0;
    private static final int STATE_INSERT = 1;

    // 核心变量
    private int mState;
    private Uri mUri;
    private Cursor mCursor;
    private EditText mText; // 笔记内容编辑框（原有）
    private String mOriginalContent;
    private String mOriginalTitle; // 新增：原始标题
    private String mOriginalCategory; // 新增：原始分类
    private Spinner mCategorySpinner; // 新增：分类选择器
    private ImageButton mAddCategoryButton; // 添加自定义分类按钮
    private ArrayAdapter<String> mCategoryAdapter; // 新增：分类适配器
    private SharedPreferences mSharedPreferences; // 用于存储自定义分类
    private static final String PREFS_NAME = "NotePadPrefs";
    private static final String KEY_CATEGORIES = "categories";

    // 新增：附件相关常量
    private static final int REQUEST_SELECT_FILE = 100;
    private static final String[] SUPPORTED_MIME_TYPES = {
            "image/*",  // 图片
            "audio/*",  // 音频
            "video/*"   // 视频
    };

    // 新增：UI控件（匹配新布局）
    private long mNoteId; // 当前笔记ID
    private Button mBtnAddAttachment; // 添加附件按钮
    private LinearLayout mAttachmentContainer; // 附件预览容器
    private TextView mNoteModifyTime; // 笔记修改时间戳

    /**
     * 原有：带行线的EditText（完全保留）
     */
    public static class LinedEditText extends EditText {
        private Rect mRect;
        private Paint mPaint;

        public LinedEditText(Context context, AttributeSet attrs) {
            super(context, attrs);
            mRect = new Rect();
            mPaint = new Paint();
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setColor(0x800000FF);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int count = getLineCount();
            Rect r = mRect;
            Paint paint = mPaint;
            for (int i = 0; i < count; i++) {
                int baseline = getLineBounds(i, r);
                canvas.drawLine(r.left, baseline + 1, r.right, baseline + 1, paint);
            }
            super.onDraw(canvas);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Intent intent = getIntent();
        final String action = intent.getAction();

        // 原有：初始化Activity状态和URI
        if (Intent.ACTION_EDIT.equals(action)) {
            mState = STATE_EDIT;
            mUri = intent.getData();
        } else if (Intent.ACTION_INSERT.equals(action) || Intent.ACTION_PASTE.equals(action)) {
            mState = STATE_INSERT;
            mUri = getContentResolver().insert(intent.getData(), null);
            if (mUri == null) {
                Log.e(TAG, "Failed to insert new note into " + getIntent().getData());
                finish();
                return;
            }
            setResult(RESULT_OK, (new Intent()).setAction(mUri.toString()));
        } else {
            Log.e(TAG, "Unknown action, exiting");
            finish();
            return;
        }

        // 原有：查询笔记数据（PROJECTION已包含修改时间）
        mCursor = managedQuery(
                mUri,
                PROJECTION,
                null,
                null,
                null
        );

        // 原有：处理粘贴动作
        if (Intent.ACTION_PASTE.equals(action)) {
            performPaste();
            mState = STATE_EDIT;
        }

        // 设置布局（使用新的note_editor.xml）
        setContentView(R.layout.note_editor);

        // 原有：绑定内容编辑框
        mText = (EditText) findViewById(R.id.note);

        // 新增：绑定新布局中的UI控件
        mBtnAddAttachment = (Button) findViewById(R.id.btn_add_attachment);
        mAttachmentContainer = (LinearLayout) findViewById(R.id.attachment_container);
       // mNoteModifyTime = (TextView) findViewById(R.id.note_modify_time); // 修复：取消注释这行代码
        mCategorySpinner = (Spinner) findViewById(R.id.category_spinner); // 新增：绑定分类选择器

        // 新增：初始化分类选择器
        mAddCategoryButton = (ImageButton) findViewById(R.id.add_category_button);

        // 加载分类列表
        mSharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        loadCategories();

        // 设置添加分类按钮的点击事件
        mAddCategoryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddCategoryDialog();
            }
        });

        // 新增：获取当前笔记ID（用于关联附件）
        if (mUri != null) {
            mNoteId = ContentUris.parseId(mUri);
        }

        // 新增：添加附件按钮点击事件
        mBtnAddAttachment.setOnClickListener(v -> selectFile());

        // 恢复保存的状态
        if (savedInstanceState != null) {
            mOriginalContent = savedInstanceState.getString(ORIGINAL_CONTENT);
            mOriginalTitle = savedInstanceState.getString(ORIGINAL_TITLE);
            mOriginalCategory = savedInstanceState.getString(ORIGINAL_CATEGORY);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mCursor != null) {
            mCursor.requery();
            mCursor.moveToFirst();

            // 原有：设置Activity标题
            if (mState == STATE_EDIT) {
                int colTitleIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_TITLE);
                String title = mCursor.getString(colTitleIndex);
                Resources res = getResources();
                String text = String.format(res.getString(R.string.title_edit), title);
                setTitle(text);
            } else if (mState == STATE_INSERT) {
                setTitle(getText(R.string.title_create));
            }

            // 原有：加载笔记内容
            int colNoteIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_NOTE);
            String note = mCursor.getString(colNoteIndex);
            mText.setTextKeepState(note);

            // 新增：加载并显示修改时间戳
            int colModifyTimeIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE);
            long modifyTime = mCursor.getLong(colModifyTimeIndex);
            if (mNoteModifyTime != null) {
                mNoteModifyTime.setText(formatTime(modifyTime)); // 格式化时间显示
            }

            // 保存原始数据
            if (mOriginalContent == null) {
                mOriginalContent = note;
            }
            if (mOriginalTitle == null) {
                int colTitleIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_TITLE);
                mOriginalTitle = mCursor.getString(colTitleIndex);
            }
            // 新增：加载和保存分类
            int colCategoryIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_CATEGORY);
            String category = mCursor.getString(colCategoryIndex);
            if (category != null) {
                // 查找分类在数组中的位置
                int position = mCategoryAdapter.getPosition(category);
                if (position != -1) {
                    mCategorySpinner.setSelection(position);
                } else {
                    // 如果分类不在预定义列表中，显示为默认
                    mCategorySpinner.setSelection(0);
                }
            } else {
                // 默认分类
                mCategorySpinner.setSelection(0);
            }
            if (mOriginalCategory == null) {
                mOriginalCategory = category;
            }

            // 新增：加载附件列表
            loadAttachments();

        } else {
            setTitle(getText(R.string.error_title));
            mText.setText(getText(R.string.error_message));
            if (mNoteModifyTime != null) {
                mNoteModifyTime.setText(""); // 异常时清空时间戳
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(ORIGINAL_CONTENT, mOriginalContent);
        outState.putString(ORIGINAL_TITLE, mOriginalTitle);
        outState.putString(ORIGINAL_CATEGORY, mOriginalCategory);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause() called, mCursor: " + (mCursor != null) + ", mState: " + mState);
        if (mCursor != null) {
            String text = mText.getText().toString();
            int length = text.length();
            Log.d(TAG, "文本长度: " + length + ", 正在退出: " + isFinishing());
            // 原有：空内容删除笔记
            if (isFinishing() && (length == 0)) {
                setResult(RESULT_CANCELED);
                deleteNote();
            } else if (mState == STATE_EDIT) {
                Log.d(TAG, "更新编辑的笔记");
                updateNote(text, null);
            } else if (mState == STATE_INSERT) {
                updateNote(text, text);
                mState = STATE_EDIT;
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.editor_options_menu, menu);

        if (mState == STATE_EDIT) {
            Intent intent = new Intent(null, mUri);
            intent.addCategory(Intent.CATEGORY_ALTERNATIVE);
            menu.addIntentOptions(Menu.CATEGORY_ALTERNATIVE, 0, 0,
                    new ComponentName(this, NoteEditor.class), null, intent, 0, null);
        }

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        int colNoteIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_NOTE);
        String savedNote = mCursor.getString(colNoteIndex);
        String currentNote = mText.getText().toString();
        if (savedNote.equals(currentNote)) {
            menu.findItem(R.id.menu_revert).setVisible(false);
        } else {
            menu.findItem(R.id.menu_revert).setVisible(true);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_save) {
            String text = mText.getText().toString();
            updateNote(text, null);
            finish();
        } else if (id == R.id.menu_delete) {
            deleteNote();
            finish();
        } else if (id == R.id.menu_revert) {
            cancelNote();
        }
        return super.onOptionsItemSelected(item);
    }

    // 原有：粘贴功能（适配新的PROJECTION）
    private final void performPaste() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ContentResolver cr = getContentResolver();
        ClipData clip = clipboard.getPrimaryClip();
        if (clip != null) {
            String text = null;
            String title = null;
            ClipData.Item item = clip.getItemAt(0);
            Uri uri = item.getUri();

            if (uri != null && NotePad.Notes.CONTENT_ITEM_TYPE.equals(cr.getType(uri))) {
                Cursor orig = cr.query(uri, PROJECTION, null, null, null);
                if (orig != null) {
                    if (orig.moveToFirst()) {
                        int colNoteIndex = orig.getColumnIndex(NotePad.Notes.COLUMN_NAME_NOTE);
                        int colTitleIndex = orig.getColumnIndex(NotePad.Notes.COLUMN_NAME_TITLE);
                        text = orig.getString(colNoteIndex);
                        title = orig.getString(colTitleIndex);
                    }
                    orig.close();
                }
            }

            if (text == null) {
                text = item.coerceToText(this).toString();
            }

            updateNote(text, title);
        }
    }

    // 原有：更新笔记（保留逻辑）
    private final void updateNote(String text, String title) {
        ContentValues values = new ContentValues();
        values.put(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, System.currentTimeMillis());

        if (mState == STATE_INSERT) {
            if (title == null) {
                int length = text.length();
                title = text.substring(0, Math.min(30, length));
                if (length > 30) {
                    int lastSpace = title.lastIndexOf(' ');
                    if (lastSpace > 0) {
                        title = title.substring(0, lastSpace);
                    }
                }
            }
            values.put(NotePad.Notes.COLUMN_NAME_TITLE, title);
        } else if (title != null) {
            values.put(NotePad.Notes.COLUMN_NAME_TITLE, title);
        }

        values.put(NotePad.Notes.COLUMN_NAME_NOTE, text);
        // 新增：保存分类
        String category = (String) mCategorySpinner.getSelectedItem();
        values.put(NotePad.Notes.COLUMN_NAME_CATEGORY, category);

        getContentResolver().update(
                mUri,
                values,
                null,
                null
        );
    }

    // 原有：撤销修改（保留逻辑）
    private final void cancelNote() {
        if (mCursor != null) {
            if (mState == STATE_EDIT) {
                mCursor.close();
                mCursor = null;
                ContentValues values = new ContentValues();
                values.put(NotePad.Notes.COLUMN_NAME_NOTE, mOriginalContent);
                values.put(NotePad.Notes.COLUMN_NAME_TITLE, mOriginalTitle); // 恢复标题
                values.put(NotePad.Notes.COLUMN_NAME_CATEGORY, mOriginalCategory); // 恢复分类
                getContentResolver().update(mUri, values, null, null);
            } else if (mState == STATE_INSERT) {
                deleteNote();
            }
        }
        setResult(RESULT_CANCELED);
        finish();
    }

    // 原有：删除笔记（保留逻辑）
    private final void deleteNote() {
        if (mCursor != null) {
            mCursor.close();
            mCursor = null;
            getContentResolver().delete(mUri, null, null);
            mText.setText("");
            if (mNoteModifyTime != null) {
                mNoteModifyTime.setText(""); // 清空时间戳
            }
            // 删除笔记时同时删除关联附件
            deleteAttachments();
        }
    }

    // ====================================== 新增功能：附件管理 + 时间格式化 ======================================

    /**
     * 打开文件选择器选择附件
     */
    private void selectFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, SUPPORTED_MIME_TYPES);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "选择附件"), REQUEST_SELECT_FILE);
    }

    /**
     * 处理文件选择结果
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SELECT_FILE && resultCode == RESULT_OK && data != null) {
            Uri fileUri = data.getData();
            if (fileUri != null) {
                saveAttachment(fileUri);
            }
        }
    }

    /**
     * 保存附件（复制到私有目录 + 插入数据库）
     */
    private void saveAttachment(Uri fileUri) {
        try {
            // 获取文件信息
            String fileName = getFileName(fileUri);
            String fileType = getContentResolver().getType(fileUri);
            long fileSize = getContentResolver().openInputStream(fileUri).available();

            // 生成唯一文件名
            String destFileName = "attachment_" + System.currentTimeMillis() + "_" + fileName;
            // 复制到应用私有目录
            copyFileToPrivateDir(fileUri, destFileName);

            // 插入附件数据到数据库
            ContentValues values = new ContentValues();
            values.put(NotePad.Attachments.COLUMN_NAME_NOTE_ID, mNoteId);
            values.put(NotePad.Attachments.COLUMN_NAME_FILE_TYPE, fileType);
            values.put(NotePad.Attachments.COLUMN_NAME_FILE_PATH, destFileName);
            values.put(NotePad.Attachments.COLUMN_NAME_FILE_NAME, fileName);
            values.put(NotePad.Attachments.COLUMN_NAME_FILE_SIZE, fileSize);

            getContentResolver().insert(NotePad.Attachments.CONTENT_URI, values);

            // 刷新附件列表
            loadAttachments();
            Toast.makeText(this, "附件添加成功", Toast.LENGTH_SHORT).show();

        } catch (IOException e) {
            Log.e(TAG, "保存附件失败", e);
            Toast.makeText(this, "附件添加失败", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 加载当前笔记的所有附件
     */
    private void loadAttachments() {
        mAttachmentContainer.removeAllViews();

        // 查询当前笔记的附件
        Uri attachmentsUri = Uri.parse(NotePad.Notes.CONTENT_URI + "/" + mNoteId + "/attachments");
        Cursor cursor = getContentResolver().query(
                attachmentsUri,
                new String[]{
                        NotePad.Attachments._ID,
                        NotePad.Attachments.COLUMN_NAME_FILE_TYPE,
                        NotePad.Attachments.COLUMN_NAME_FILE_NAME,
                        NotePad.Attachments.COLUMN_NAME_FILE_SIZE
                },
                null, null, null);

        if (cursor != null) {
            while (cursor.moveToNext()) {
                long attachmentId = cursor.getLong(0);
                String fileType = cursor.getString(1);
                String fileName = cursor.getString(2);
                long fileSize = cursor.getLong(3);

                // 创建附件预览视图并添加到容器
                View attachmentView = createAttachmentView(attachmentId, fileType, fileName, fileSize);
                mAttachmentContainer.addView(attachmentView);
            }
            cursor.close();
        }
    }

    /**
     * 创建单个附件的预览视图
     */
    /**
     * 创建单个附件的预览视图
     */
    /**
     * 创建单个附件的预览视图（简化版）
     */
    private View createAttachmentView(long attachmentId, String fileType, String fileName, long fileSize) {
        // 使用更简单的布局
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setPadding(16, 8, 16, 8);

        // 添加图标
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_menu_save);
        icon.setLayoutParams(new LinearLayout.LayoutParams(40, 40));
        view.addView(icon);

        // 添加文件名
        TextView textView = new TextView(this);
        textView.setText(fileName + " (" + formatFileSize(fileSize) + ")");
        textView.setPadding(16, 0, 0, 0);
        view.addView(textView);

        // 简单的点击事件
        view.setOnClickListener(v -> {
            Toast.makeText(NoteEditor.this, "附件: " + fileName, Toast.LENGTH_SHORT).show();
        });

        return view;
    }

    /**
     * 打开文件
     */
    private void openFile(String filePath, String fileType) {
        File file = getFileStreamPath(filePath);
        if (file.exists()) {
            try {
                Uri fileUri = Uri.fromFile(file);

                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(fileUri, fileType);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                // 检查是否有应用可以处理该文件
                if (intent.resolveActivity(getPackageManager()) != null) {
                    startActivity(intent);
                } else {
                    Toast.makeText(this, "没有应用可以打开此文件", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Log.e(TAG, "打开文件失败: " + e.getMessage());
                Toast.makeText(this, "打开文件失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 设置默认图标
     */
    private void setDefaultIcon(ImageView imageView, String fileType) {
        if (fileType != null) {
            if (fileType.startsWith("image/")) {
                imageView.setImageResource(R.drawable.live_folder_notes);
            } else if (fileType.startsWith("audio/")) {
                imageView.setImageResource(R.drawable.ic_menu_compose);
            } else if (fileType.startsWith("video/")) {
                imageView.setImageResource(R.drawable.ic_menu_save);
            } else {
                imageView.setImageResource(R.drawable.ic_menu_save); // 默认图标
            }
        } else {
            imageView.setImageResource(R.drawable.ic_menu_save);
        }
    }

    /**
     * 辅助：从URI解析文件名
     */
    private String getFileName(Uri uri) {
        String fileName = "unknown_file";
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            Cursor cursor = getContentResolver().query(
                    uri,
                    new String[]{MediaStore.MediaColumns.DISPLAY_NAME},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                fileName = cursor.getString(0);
                cursor.close();
            }
        } else {
            fileName = uri.getLastPathSegment();
        }
        return fileName;
    }

    /**
     * 辅助：复制文件到应用私有目录
     */
    private void copyFileToPrivateDir(Uri sourceUri, String destFileName) throws IOException {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = getContentResolver().openInputStream(sourceUri);
            out = openFileOutput(destFileName, MODE_PRIVATE);

            byte[] buffer = new byte[4096];
            int length;
            while ((length = in.read(buffer)) != -1) {
                out.write(buffer, 0, length);
            }
            out.flush();
        } finally {
            if (in != null) in.close();
            if (out != null) out.close();
        }
    }

    /**
     * 辅助：格式化文件大小（B/KB/MB/GB）
     */
    private String formatFileSize(long size) {
        DecimalFormat df = new DecimalFormat("#.00");
        if (size < 1024) {
            return df.format(size) + " B";
        } else if (size < 1024 * 1024) {
            return df.format((double) size / 1024) + " KB";
        } else if (size < 1024 * 1024 * 1024) {
            return df.format((double) size / (1024 * 1024)) + " MB";
        } else {
            return df.format((double) size / (1024 * 1024 * 1024)) + " GB";
        }
    }

    /**
     * 辅助：格式化时间戳为可读字符串
     */
    private String formatTime(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }

    /**
     * 辅助：根据附件ID获取文件路径
     */
    private String getAttachmentFilePath(long attachmentId) {
        Uri attachmentUri = ContentUris.withAppendedId(NotePad.Attachments.CONTENT_URI, attachmentId);
        Cursor cursor = getContentResolver().query(
                attachmentUri,
                new String[]{NotePad.Attachments.COLUMN_NAME_FILE_PATH},
                null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            String path = cursor.getString(0);
            cursor.close();
            return path;
        }
        return null;
    }

    /**
     * 辅助：删除当前笔记的所有附件（文件+数据库记录）
     */
    private void deleteAttachments() {
        Uri attachmentsUri = Uri.parse(NotePad.Notes.CONTENT_URI + "/" + mNoteId + "/attachments");
        Cursor cursor = getContentResolver().query(
                attachmentsUri,
                new String[]{NotePad.Attachments._ID, NotePad.Attachments.COLUMN_NAME_FILE_PATH},
                null, null, null);
        if (cursor != null) {
            while (cursor.moveToNext()) {
                long attachmentId = cursor.getLong(0);
                String filePath = cursor.getString(1);
                // 删除文件
                File file = getFileStreamPath(filePath);
                if (file.exists()) {
                    file.delete();
                }
                // 删除数据库记录
                Uri attachmentUri = ContentUris.withAppendedId(NotePad.Attachments.CONTENT_URI, attachmentId);
                getContentResolver().delete(attachmentUri, null, null);
            }
            cursor.close();
        }
    }

    /**
     * 加载分类列表
     */
    private void loadCategories() {
        // 基础分类列表
        String[] baseCategories = {"默认", "工作", "生活", "学习", "其他"};

        // 获取保存的自定义分类
        String savedCategories = mSharedPreferences.getString(KEY_CATEGORIES, "");

        // 合并分类列表
        java.util.List<String> categoriesList = new java.util.ArrayList<>();
        java.util.Collections.addAll(categoriesList, baseCategories);

        if (!savedCategories.isEmpty()) {
            String[] customCategories = savedCategories.split(";" + System.lineSeparator());
            for (String category : customCategories) {
                if (!category.trim().isEmpty() && !categoriesList.contains(category.trim())) {
                    categoriesList.add(category.trim());
                }
            }
        }

        // 创建分类适配器并设置到Spinner
        mCategoryAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, categoriesList);
        mCategoryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mCategorySpinner.setAdapter(mCategoryAdapter);
    }

    /**
     * 显示添加自定义分类对话框
     */
    private void showAddCategoryDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("添加自定义分类");

        // 创建输入框
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        builder.setView(input);

        // 设置按钮
        builder.setPositiveButton("保存", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String newCategory = input.getText().toString().trim();
                if (!newCategory.isEmpty()) {
                    // 获取当前保存的分类
                    String savedCategories = mSharedPreferences.getString(KEY_CATEGORIES, "");
                    // 检查是否已存在
                    if (!savedCategories.contains(newCategory)) {
                        // 保存新分类
                        if (!savedCategories.isEmpty()) {
                            savedCategories += ";\n" + newCategory;
                        } else {
                            savedCategories = newCategory;
                        }
                        Editor editor = mSharedPreferences.edit();
                        editor.putString(KEY_CATEGORIES, savedCategories);
                        editor.apply();
                        // 重新加载分类列表
                        loadCategories();
                    } else {
                        Toast.makeText(NoteEditor.this, "分类已存在", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });

        builder.setNegativeButton("取消", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        builder.show();
    }
}