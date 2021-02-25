
# Sample OpenCV project

Sample project to check OpenCV face detection functionality and MJPEG protocol for displaying
video frames sequence in the browser.

The app reads default camera (with index 0) and displays the result on the web page. After
starting the server the page can be accessed with `http://localhost:8080/` . Works in
Chrome.

OpenCV binaries have been build for Linux x64 only.

To run the main class add `-Djava.library.path=./lib-native` to the run configuration
or use Eclipse run configuration bundled with the project.

OpenCV and its derivatives are covered by OpenCV license. See `OpenCV-LICENSE` file -
it have been copied from OpenCV project on GitHub -
https://raw.githubusercontent.com/opencv/opencv/master/LICENSE


## Building OpenCV Java artifacts in Ubuntu Docker image

1. Run Ubuntu in Docker, mounting host machine folder (e.g. /tmp/tmp_workspace) to
the container folder (e.g. /tmp_workspace). Mounting is needed to fetch generated artifacts
later on.

```
docker run -it --rm --name opencv_build --mount type=bind,source="/tmp/tmp_workspace",target=/tmp_workspace ubuntu:20.04
```

2. In the container console:

 - 'j8' in make call - number of active threads
 - python3 is required for Java to be detected by cmake

```
apt update && apt install git wget g++ cmake openjdk-11-jdk ant python3
export ANT_HOME=/usr/share/ant/
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
cd /tmp_workspace
git clone --depth 1 --branch 4.5.1 https://github.com/opencv/opencv.git
mkdir build; cd build
cmake -D BUILD_SHARED_LIBS=OFF ../opencv
make -j8
```
Result on host machine:

 - /tmp/tmp_workspace/build/bin/opencv-451.jar - put into the project ./lib folder
 - /tmp/tmp_workspace/build/lib/libopencv_java451.so - put into the project ./lib-native folder
 
 