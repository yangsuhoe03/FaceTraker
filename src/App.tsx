import React, { useEffect, useState } from 'react';
import { StyleSheet, View, Text, PermissionsAndroid, Platform } from 'react-native';
import { 
  Camera, 
  useCameraDevice, 
  useFrameProcessor, 
  runAtTargetFps 
} from 'react-native-vision-camera';
import { VisionCameraProxy, Frame } from 'react-native-vision-camera';
import { Worklets } from 'react-native-worklets-core';

// [중요] Worklets용 함수 선언
// UI 스레드에서 실행될 함수
function updateFaceData(setNosePos: any, x: number, y: number) {
  'worklet';
  runOnJS(setNosePos)({ x, y });
}

// React Native Reanimated의 runOnJS가 없다면 직접 만들어야 할 수도 있지만, 
// 여기서는 간단히 console.log로 테스트하거나 Worklets.createRunOnJS를 사용.
import { runOnJS } from 'react-native-reanimated'; 
// Reanimated가 없다면 설치해야 합니다. 일단 설치되어 있다고 가정하거나 
// Vision Camera는 기본적으로 Reanimated 의존성을 가질 수 있음. 
// 만약 에러나면 Reanimated 설치 필요.

const App = () => {
  const device = useCameraDevice('front');
  const [hasPermission, setHasPermission] = useState(false);
  const [nosePos, setNosePos] = useState({ x: 0, y: 0 });

  useEffect(() => {
    const checkPermission = async () => {
      const status = await Camera.requestCameraPermission();
      setHasPermission(status === 'granted');
    };
    checkPermission();
  }, []);

  const frameProcessor = useFrameProcessor((frame) => {
    'worklet';
    
    // 네이티브 플러그인 호출
    // @ts-ignore
    const result = frame.detectFace(); 

    if (result) {
      console.log(`Detected face: ${result.noseX}, ${result.noseY}`);
      // UI 업데이트 (너무 자주 하면 느려지므로 주의)
      runOnJS(setNosePos)({ x: result.noseX, y: result.noseY });
    }
  }, []);

  if (!hasPermission) return <Text>No Permission</Text>;
  if (device == null) return <Text>No Device</Text>;

  return (
    <View style={styles.container}>
      <Camera
        style={StyleSheet.absoluteFill}
        device={device}
        isActive={true}
        frameProcessor={frameProcessor}
        pixelFormat="yuv" // 호환성 최강
      />
      <View style={styles.infoBox}>
        <Text style={styles.text}>
          코 위치: {nosePos.x.toFixed(2)}, {nosePos.y.toFixed(2)}
        </Text>
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: 'black',
  },
  infoBox: {
    position: 'absolute',
    top: 50,
    left: 20,
    backgroundColor: 'rgba(0,0,0,0.5)',
    padding: 10,
  },
  text: {
    color: 'white',
    fontSize: 20,
  }
});

export default App;