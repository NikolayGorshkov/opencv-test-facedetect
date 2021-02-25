package org.ng.opencv.test.facedetect;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfRect;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;
import org.opencv.videoio.VideoCapture;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;

/**
 * For info on face detection see:
 * https://docs.opencv.org/4.5.1/db/d28/tutorial_cascade_classifier.html (has
 * examples in Java). OpenCV-related logic taken from this example.
 */
public class Main {

	private static final Logger log = Logger.getLogger(Main.class.getName());

	private static volatile Mat lastFrame;

	private static final List<Path> filesToDelete = new ArrayList<>();

	static {
		System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
	}

	public static void main(String[] args) throws Exception {

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			filesToDelete.forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (Exception e) {
					log.log(Level.SEVERE, "Error deleting file " + p, e);
				}
			});
		}));

		// XML files taken from:
		// https://github.com/opencv/opencv/tree/master/data/haarcascades
		CascadeClassifier faceCascade = initCascade("haarcascades/haarcascade_frontalface_alt.xml");
		CascadeClassifier eyesCascade = initCascade("haarcascades/haarcascade_eye_tree_eyeglasses.xml");

		startServer();
		VideoCapture camera = new VideoCapture();
		try {
			camera.open(0);
//			camera.set(Videoio.CAP_PROP_FRAME_WIDTH, 300);
			for (;;) {
				Mat frame = new Mat();
				if (!camera.read(frame)) {
					continue;
				}

				Mat frameGray = new Mat();
				Imgproc.cvtColor(frame, frameGray, Imgproc.COLOR_BGR2GRAY);
//				Imgproc.equalizeHist(frameGray, frameGray); // this makes eyes detection on low-level camera worse // NG

				MatOfRect faces = new MatOfRect();
				faceCascade.detectMultiScale(frameGray, faces);
				List<Rect> listOfFaces = faces.toList();
				for (Rect face : listOfFaces) {
					Point center = new Point(face.x + face.width / 2, face.y + face.height / 2);
					Imgproc.ellipse(frame, center, new Size(face.width / 2, face.height / 2), 0, 0, 360,
							new Scalar(255, 0, 255));
					Mat faceROI = frameGray.submat(face);
					// -- In each face, detect eyes
					MatOfRect eyes = new MatOfRect();
					eyesCascade.detectMultiScale(faceROI, eyes);
					List<Rect> listOfEyes = eyes.toList();
					for (Rect eye : listOfEyes) {
						Point eyeCenter = new Point(face.x + eye.x + eye.width / 2, face.y + eye.y + eye.height / 2);
						int radius = (int) Math.round((eye.width + eye.height) * 0.25);
						Imgproc.circle(frame, eyeCenter, radius, new Scalar(255, 0, 0), 4);
					}
				}

				lastFrame = frame;
				Thread.sleep(40);
			}
		} finally {
			camera.release();
		}
	}

	private static CascadeClassifier initCascade(String xmlPath) throws Exception {
		Path tempFile = Files.createTempFile("haarcascade", xmlPath.replaceAll("[\\/]", "_"));
		filesToDelete.add(tempFile);
		CascadeClassifier result = new CascadeClassifier();
		try (OutputStream out = Files.newOutputStream(tempFile)) {
			Main.class.getClassLoader().getResourceAsStream(xmlPath).transferTo(out);
		}
		result.load(tempFile.toAbsolutePath().toString());
		Files.deleteIfExists(tempFile);
		return result;
	}

	private static void startServer() {
		final int port = 8080;
		final String imageFormat = "jpg"; // works with "png" and "bmp" as well

		log.info("Server - starting on port " + port);

		Vertx vertx = Vertx.vertx();

		/*
		 * I used TCP server instead of HTTP since I couldn't find any way to send
		 * multipart response with Vert.x HTTP implementation. It either requires
		 * content-length, or demands transfer-encoding: chunked.
		 * 
		 * I haven't tried chunked transfer encoding with final implementation of
		 * MJPEG frames encoding, but working with TCP is fun :)
		 */
		NetServer tcpServer = vertx.createNetServer();

		tcpServer.connectHandler(socket -> {
			Buffer requestBuf = Buffer.buffer();
			socket.handler(buf -> {
				requestBuf.appendBuffer(buf);
				if (!(requestBuf.length() > 4 && requestBuf
						.getString(requestBuf.length() - 4, requestBuf.length(), "UTF-8").equals("\r\n\r\n"))) {
					return;
				}
				String requestStr = requestBuf.getString(0, requestBuf.length(), "UTF-8");
//				System.out.println("REQUEST:\n" + requestStr);
				String firstLine = requestStr.substring(0, requestStr.indexOf('\n')).trim();
				String[] firstLineParts = firstLine.split(" ");
				if (!"GET".equals(firstLineParts[0])) {
					socket.write("HTTP/1.1 500 Internal server error\r\n" //
							+ "Cache-control: no-store\r\n" //
							+ "Content-Type: text/plain\r\n" //
							+ "Content-Length: 5\r\n\r\n"//
							+ "ERROR").onComplete(result -> {
								socket.close();
							});
					throw new IllegalStateException("Unsupported method: " + firstLineParts[0]);
				}
				if ("/".equals(firstLineParts[1])) {
					ByteArrayOutputStream baos = new ByteArrayOutputStream();
					try {
						Main.class.getClassLoader().getResourceAsStream("webroot/index.html").transferTo(baos);
					} catch (IOException e) {
						throw new IllegalStateException("Error reading index.html content", e);
					}
					byte[] indexHtml = baos.toByteArray();
					socket.write("HTTP/1.1 200 OK\r\n" + "Cache-control: no-store\r\n" //
							+ "Content-Type: text/html; encoding=utf-8\r\n" //
							+ "Content-Length: " + indexHtml.length //
							+ "\r\n\r\n").compose(v -> {
								return socket.write(Buffer.buffer(indexHtml));
							}).onComplete(result -> {
								socket.close();
							});
					return;
				}
				if ("/frames".equals(firstLineParts[1])) {
					String boundary = "MJPEG-DATA";
					AtomicReference<Future<Void>> future = new AtomicReference<>(socket.write("HTTP/1.1 200 OK\r\n" //
							+ "Cache-control: no-store\r\n" //
							+ "Content-Type: multipart/x-mixed-replace; boundary=\"" + boundary + "\"\r\n\r\n"));
					new Thread(() -> {
						for (;;) {
							future.set(future.get().compose(v -> {
								return socket.write("--" + boundary + "\r\n" //
										+ "Content-Type: image/" + imageFormat + "\r\n\r\n");
							}).compose(v -> {
								MatOfByte result = new MatOfByte();
								Imgcodecs.imencode("." + imageFormat, lastFrame, result);

								byte[] resultArr = result.toArray();

								return socket.write(Buffer.buffer(resultArr));
							}).compose(v -> {
								return socket.write("\r\n");
							}));
							try {
								Thread.sleep(20);
							} catch (InterruptedException e) {
								log.log(Level.WARNING, "Sleep interrupted", e);
							}
						}
					}).start();
					return;
				}
				socket.write("HTTP/1.1 404 Not found\r\n" //
						+ "Cache-control: no-store\r\n" //
						+ "Content-Type: text/plain\r\n" //
						+ "Content-Length: 9\r\n\r\n" + "NOT FOUND" //
				).onComplete(result -> {
					socket.close();
				});
				throw new IllegalStateException("404: " + firstLineParts[1]);
			}).exceptionHandler(e -> {
				log.log(Level.SEVERE, "Error serving request", e);
				socket.close();
			});
		}).exceptionHandler(e -> {
			log.log(Level.SEVERE, "Error serving request", e);
		}).listen(port, "localhost");

		log.info("Server - started");
		log.info("Access the web page by URL: http://localhost:" + port);
	}

}
