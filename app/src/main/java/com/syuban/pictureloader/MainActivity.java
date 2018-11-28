package com.syuban.pictureloader;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.ImageView;

public class MainActivity extends AppCompatActivity {

    ImageView imageView;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        imageView = findViewById(R.id.image_view);
        PictureLoader.build(this).setBitmap("https://www.google.com/images/branding/googlelogo/2x/googlelogo_color_120x44dp.png", imageView);
    }
}
