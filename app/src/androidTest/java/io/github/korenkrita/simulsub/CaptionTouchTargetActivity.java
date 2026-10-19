package io.github.korenkrita.simulsub;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;

/** 测试APK独立UID的原生承载页，不依赖目标应用、Compose、网络或用户配置。 */
public final class CaptionTouchTargetActivity extends Activity {
    public static final String READY_TEXT = "透传承载页已就绪";
    public static final String CLICKED_TEXT = "透传按钮已被真实点击";

    /** 固定按钮的文字变化由本进程真实点击回调触发，供跨UID验证读取。 */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFFEAF0F2);
        Button button = new Button(this);
        button.setId(android.R.id.button1);
        button.setText(READY_TEXT);
        button.setOnClickListener(view -> button.setText(CLICKED_TEXT));
        float density = getResources().getDisplayMetrics().density;
        int width = Math.min((int) (280 * density), getResources().getDisplayMetrics().widthPixels - (int) (32 * density));
        root.addView(button, new FrameLayout.LayoutParams(width, (int) (64 * density), Gravity.CENTER));
        setContentView(root);
    }
}
