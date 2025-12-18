import { requireNativeComponent, ViewProps, HostComponent } from 'react-native';

/**
 * 네이티브에서 전달해주는 이벤트 데이터의 타입 정의
 */
interface FaceDetectedEvent {
  nativeEvent: {
    noseX: number;
    noseY: number;
  };
}

/**
 * 컴포넌트가 가질 Props 타입 정의
 */
interface NativeFaceLandmarkerViewProps extends ViewProps {
  onFaceDetected?: (event: FaceDetectedEvent) => void;
}

/**
 * 'FaceLandmarkerView'라는 이름으로 등록된 네이티브 컴포넌트를 가져옵니다.
 */
const NativeFaceLandmarkerView = requireNativeComponent<NativeFaceLandmarkerViewProps>(
  'FaceLandmarkerView'
) as HostComponent<NativeFaceLandmarkerViewProps>;

export default NativeFaceLandmarkerView;
