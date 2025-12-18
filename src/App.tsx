import React, { useEffect, useState } from 'react';
import {
  StyleSheet,
  Text,
  View,
  PermissionsAndroid,
  Platform,
  Alert,
} from 'react-native';
import NativeFaceLandmarkerView from './components/NativeFaceLandmarkerView';

const App = () => {
  const [hasPermission, setHasPermission] = useState(false);
  const [nosePos, setNosePos] = useState({ x: 0, y: 0 });

  // 앱 시작 시 카메라 권한 요청
  useEffect(() => {
    requestCameraPermission();
  }, []);

  const requestCameraPermission = async () => {
    if (Platform.OS === 'android') {
      try {
        const granted = await PermissionsAndroid.request(
          PermissionsAndroid.PERMISSIONS.CAMERA,
          {
            title: '카메라 권한 요청',
            message: '얼굴 인식을 위해 카메라 권한이 필요합니다.',
            buttonPositive: '허용',
          },
        );
        if (granted === PermissionsAndroid.RESULTS.GRANTED) {
          setHasPermission(true);
        } else {
          Alert.alert('권한 거부', '카메라 권한이 필요합니다.');
        }
      } catch (err) {
        console.warn(err);
      }
    }
  };

  const handleFaceDetected = (event: any) => {
    const { noseX, noseY } = event.nativeEvent;
    setNosePos({ x: noseX, y: noseY });
  };

  return (
    <View style={styles.container}>
      {hasPermission ? (
        <View style={styles.cameraContainer}>
          {/* 네이티브 카메라 뷰: absoluteFillObject로 꽉 채움 */}
          <NativeFaceLandmarkerView
            style={StyleSheet.absoluteFillObject}
            onFaceDetected={handleFaceDetected}
          />
          
          {/* 정보 표시 레이어 */}
          <View style={styles.infoBox}>
            <Text style={styles.infoText}>상태: AI 엔진 가동 중</Text>
            <Text style={styles.dataText}>
              코 좌표: {nosePos.x.toFixed(4)}, {nosePos.y.toFixed(4)}
            </Text>
            {nosePos.x !== 0 && (
              <Text style={{color: '#ff0', fontWeight: 'bold'}}>얼굴 감지됨!</Text>
            )}
          </View>
        </View>
      ) : (
        <View style={styles.centered}>
          <Text style={{color: 'white'}}>카메라 권한 승인이 필요합니다.</Text>
        </View>
      )}
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000',
  },
  cameraContainer: {
    flex: 1,
    backgroundColor: '#111', // 검은 화면인지 확인용 배경색
  },
  centered: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  infoBox: {
    position: 'absolute',
    top: 60,
    left: 20,
    backgroundColor: 'rgba(0,0,0,0.7)',
    padding: 15,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#444',
  },
  infoText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: 'bold',
  },
  dataText: {
    color: '#0f0',
    fontSize: 14,
    marginTop: 5,
    fontFamily: Platform.OS === 'ios' ? 'Courier' : 'monospace',
  },
});

export default App;
