/*
 * Copyright (C) 2010 The Android Open Source Project
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
 * Based on http://code.google.com/p/android-imagedownloader/
 * Updated by Harry Tormey   <harry@catch.com>
 */

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;

import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.io.InputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Build;
import android.util.Log;
import android.util.DisplayMetrics;
import android.widget.ImageView;
import android.view.WindowManager;

public class FacebookImageLoader {
	// These are for 1.5 backwards compatibility which doesn't have
	// these definied in DisplayMetrics
	public static final int DENSITY_LOW = 120;
	public static final int DENSITY_MEDIUM = 160;
	public static final int DENSITY_HIGH = 240;

	private static final String LOGCAT_NAME = "FacebookImageLoader";
	private static final int BUFFER_SIZE = 8 * 1024;
	private static final String BASE_URL = "http://graph.facebook.com/";
	private static final String PICTURE = "/picture";
	private static int mDensityDpi = 0;
	private Context mContext;
	private int mMaxDimension;

	public FacebookImageLoader(Context context) {
		mContext = context;
		mMaxDimension = getMaxThumbnailDimension(mContext, false);
	}

	public void load(String filename, ImageView imageView) {
		Bitmap bitmap = getBitmapFromCache(filename);
		if (bitmap == null) {
			forceLoad(filename, imageView);
		} else {
			imageView.setImageBitmap(bitmap);
		}
	}

	private void forceLoad(String filename, ImageView imageView) {
		// State sanity: filename is guaranteed to never be null in LoadedDrawable and cache keys.
		if (filename == null) {
			imageView.setImageDrawable(null);
			return;
		}

		BitmapLoaderTask task = new BitmapLoaderTask(imageView);
		//This is where we tie a reference to the image filename to ImageView.
		LoadedDrawable downloadedDrawable = new LoadedDrawable(filename);
		imageView.setImageDrawable(downloadedDrawable);
		task.execute(filename);
	}

	//Check to see if given filename matches that associated with ImageView. We need this because Listview recycles ImageViews.
	private static boolean checkImageViewFileName(ImageView imageView, String filename) {
		if (imageView != null) {
			Drawable drawable = imageView.getDrawable();

			if (drawable instanceof LoadedDrawable) {
				LoadedDrawable loadedDrawable = (LoadedDrawable)drawable;
				return loadedDrawable.checkFilname(filename);
			}
		}
		return false;
	}

	Bitmap downloadBitmap(final String url, File cacheFile) {
		// HttpClient works with older Android versions.
		final HttpClient client = new DefaultHttpClient();
		final HttpGet getRequest = new HttpGet(url);
		try {
			HttpResponse response = client.execute(getRequest);
			final int statusCode = response.getStatusLine().getStatusCode();
			if (statusCode != HttpStatus.SC_OK) {
				Log.w(LOGCAT_NAME, "Error " + statusCode + " while retrieving bitmap from " + url);
				return null;
			}

			final HttpEntity entity = response.getEntity();
			if (entity != null) {
				InputStream inputStream = null;
				try {
					inputStream = entity.getContent();
					FlushedInputStream in = new FlushedInputStream(inputStream);
					if(cacheFile != null && cacheFile.exists()){
						FileOutputStream fos = new FileOutputStream(cacheFile);
						while(true){
							int bytedata = in.read();
							if(bytedata == -1)
								 break;
							fos.write(bytedata);
						}
						fos.close();
						return BitmapFactory.decodeFile(cacheFile.getAbsolutePath());
					}
				} finally {
					if (inputStream != null) {
						inputStream.close();
					}
					entity.consumeContent();
				}
			}
		} catch (IOException e) {
			getRequest.abort();
			Log.w(LOGCAT_NAME, "I/O error while retrieving bitmap from " + url, e);
		} catch (IllegalStateException e) {
			getRequest.abort();
			Log.w(LOGCAT_NAME, "Incorrect URL: " + url);
		} catch (Exception e) {
			getRequest.abort();
			Log.w(LOGCAT_NAME, "Error while retrieving bitmap from " + url, e);
		} finally {
		}
		return null;
	}

	private Bitmap loadBitmap(String filename) {
		//First check if file exists, if not try and do the facebook fetch
		Bitmap bitmap = null;
		File cacheFile = FileUtil.getFileFromCache(mContext, filename);
		if(cacheFile != null && cacheFile.exists()){
			bitmap = loadImageFromFile(cacheFile.getPath(), mMaxDimension, true);
			return bitmap;
		}else{
			//Download from FB and cache
			cacheFile = FileUtil.addFileToCache(mContext, filename);
			final String url = BASE_URL + filename  + PICTURE;
			bitmap = downloadBitmap(url, cacheFile);
		}
		return bitmap;
	}

	//An InputStream that skips the exact number of bytes provided, unless it reaches EOF.
	static class FlushedInputStream extends FilterInputStream {
		public FlushedInputStream(InputStream inputStream) {
			super(inputStream);
		}

		@Override
		public long skip(long n) throws IOException {
			long totalBytesSkipped = 0L;
			while (totalBytesSkipped < n) {
				long bytesSkipped = in.skip(n - totalBytesSkipped);
				if (bytesSkipped == 0L) {
					int b = read();
					if (b < 0) {
						break;  // we reached EOF
					} else {
						bytesSkipped = 1; // we read one byte
					}
				}
				totalBytesSkipped += bytesSkipped;
			}
			return totalBytesSkipped;
		}
	}

	private class BitmapLoaderTask extends AsyncTask<String, Void, Bitmap> {
		private String filename;
		private final WeakReference<ImageView> imageViewReference;

		public BitmapLoaderTask(ImageView imageView) {
			imageViewReference = new WeakReference<ImageView>(imageView);
		}

		@Override
		protected Bitmap doInBackground(String... params) {
			filename = params[0];
			Bitmap bitmap = loadBitmap(filename);
			return bitmap;
		}

		@Override
		protected void onPostExecute(Bitmap bitmap) {
			if (isCancelled()) {
				bitmap = null;
			}

			ImageView imageView = imageViewReference.get();
			if (imageView != null) {
				boolean filenamesMatch = checkImageViewFileName(imageView, filename);

				if (imageView != null && filenamesMatch) {
					imageView.setImageBitmap(bitmap);
				}
			}
		}
	}

	static class LoadedDrawable extends ColorDrawable {
		private final String mFilename;

		public LoadedDrawable(String filename) {
			super(Color.TRANSPARENT);
			this.mFilename = filename;
		}

		//Check to see if filename of image downloaded matches filename associated with current ImageView
		public boolean checkFilname(String filename){
			return mFilename.equals(filename);
		}
	}

	private Bitmap getBitmapFromCache(String filename) {
		File cacheFile = FileUtil.getFileFromCache(mContext, filename);
		if(cacheFile != null && cacheFile.exists()){
			try{
				final Bitmap bm = BitmapFactory.decodeStream(new FileInputStream(cacheFile));
				return bm;
			}catch (Exception e) {
				Log.e(LOGCAT_NAME, "Error reading file: " + e.toString());
			}
		}
		return null;
	}

	public void clearCache() {
		FileUtil.cleanCaches(mContext);
	}

	// Process an an image from a file, resizing it as necessary.
	public static Bitmap loadImageFromFile(final String file, final int maxDimension, boolean exactResize) {
		// Check input
		if (file == null || file.length() == 0) {
			return null;
		}

		BufferedInputStream is = null;
		Bitmap image = null;
		
		if (exactResize) {
			// caller wants the output bitmap's max dimension scaled exactly
			// as passed in. This is slower.
			try {
				is = new BufferedInputStream(new FileInputStream(file), BUFFER_SIZE);
				image = BitmapFactory.decodeStream(is);
				is.close();
			} catch (FileNotFoundException e) {
				e.printStackTrace();
				return null;
			} catch (IOException e) {
				e.printStackTrace();
				return null;
			} catch (OutOfMemoryError e) {
				// don't return, we'll try again with an inexact resize
				image = null;
			}
			
			if (image != null) {
				return processImageFromBitmap(image, maxDimension);
			}
		} 
		
		// Caller does not need an exact image resize; we can achieve
		// "ballpark" using inSampleSize to save time and memory.
		BitmapFactory.Options opts = getImageSizeFromFile(file);
		
		if (opts == null) {
			return null;
		}
		
		// Calculate the resize ratio. Make the longest side
		// somewhere in the vicinity of 'maxDimension' pixels.
		int scaler = 1;
		int maxSide = Math.max(opts.outWidth, opts.outHeight);

		if (maxSide > maxDimension) {
			float ratio = (float) maxSide / (float) maxDimension;
			scaler = Math.round(ratio);
		}

		opts.inJustDecodeBounds = false;
		opts.inSampleSize = scaler;

		// This time we'll load the image for real, but with
		// inSampleSize set which will scale the image down as it is
		// loaded.
		try {
			is = new BufferedInputStream(new FileInputStream(file), BUFFER_SIZE);
			image = BitmapFactory.decodeStream(is, null, opts);
			is.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			return null;
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		} catch (OutOfMemoryError e) {
			e.printStackTrace();
			return null;
		}

		return image;
	}

	// Process an an image from a Bitmap already in memory,
	// resizing it as necessary.
	public static Bitmap processImageFromBitmap(final Bitmap bitmap, final int maxDimension) {
		// Check input
		if (bitmap == null) {
			return null;
		}

		// Calculate the resize ratio. Make the longest side
		// somewhere in the vicinity of 'maxDimension' pixels.
		int maxSide = Math.max(bitmap.getWidth(), bitmap.getHeight());
		float newWidth = bitmap.getWidth();
		float newHeight = bitmap.getHeight();
		float ratio = 0;
		
		if (maxSide > maxDimension) {
			ratio = (float) maxDimension / (float) maxSide;
			newWidth *= ratio;
			newHeight *= ratio;
		}
		
		try {
			return Bitmap.createScaledBitmap(bitmap,
					Math.round(newWidth),
					Math.round(newHeight), false);
		} catch (OutOfMemoryError e) {
			e.printStackTrace();
			return null;
		}
	}

	public static BitmapFactory.Options getImageSizeFromFile(final String file) {
		BitmapFactory.Options opts = new BitmapFactory.Options();
		opts.inJustDecodeBounds = true;

		// With inJustDecodeBounds set, we're just going to peek at
		// the resolution of the image.
		try {
			BufferedInputStream is = new BufferedInputStream(new FileInputStream(file), BUFFER_SIZE);
			BitmapFactory.decodeStream(is, null, opts);
			is.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			return null;
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		} catch (OutOfMemoryError e) {
			e.printStackTrace();
			return null;
		}
		
		return opts;
	}

	public static int getMaxThumbnailDimension(Context context, boolean larger) {
		if (mDensityDpi == 0) {
			calculateDensityDpi(context);
		}
		
		switch (mDensityDpi) {
			case DENSITY_LOW:
				return (larger) ? 64 : 48;
			case DENSITY_HIGH:
				return (larger) ? 128 : 96;
			case DENSITY_MEDIUM:
			default:
				return (larger) ? 96 : 64;
		}
	}

	public static int calculateDensityDpi(Context context) {
		if (mDensityDpi > 0) {
			// we've already calculated it
			return mDensityDpi;
		}
		
		if (Integer.parseInt(Build.VERSION.SDK) <= 3) {
			// 1.5 devices are all medium density
			mDensityDpi = DENSITY_MEDIUM;
			return mDensityDpi;
		}

		DisplayMetrics metrics = new DisplayMetrics();
		WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
		wm.getDefaultDisplay().getMetrics(metrics);
		int reflectedDensityDpi = DENSITY_MEDIUM;

		try {
			reflectedDensityDpi = DisplayMetrics.class.getDeclaredField("mDensityDpi").getInt(metrics);
		} catch (Exception e) {
			e.printStackTrace();
		}

		mDensityDpi = reflectedDensityDpi;
		return mDensityDpi;
	}
}
