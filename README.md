# PictureLoader

简单图片加载框架（完善中）

实现原理：内存缓存（LruCache）、磁盘缓存（DiskLruCache）、网络拉取、图片压缩、同步加载、异步加载

简单使用：

首先在项目中，添加依赖：

    allprojects {
        repositories {
            maven { url 'https://jitpack.io' }
        }
    }

    implementation 'com.github.LanceShu:PictureLoader:1.0'

在代码中，使用PictureLoader加载图片：

    PictureLoader.build(this).setBitmap(String url， ImageView iv， int reqWidth，int reqHeight);

第一个参数是图片Url，第二个参数是ImageView，后两个参数是目标图片缩放后的宽高；

效果图：

![](http://123.207.145.251:8080/SimpleBox/picture/1543413189374.jpg)
