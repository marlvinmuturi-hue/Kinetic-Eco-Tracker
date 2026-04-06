/**
 * Web Sensor Service
 * Handles motion sensors (accelerometer, gyroscope) for web browsers
 */

export interface WebSensorData {
  acceleration: number;
  rotationRate: number;
  timestamp: number;
}

export class WebSensorService {
  private isListening: boolean = false;
  private currentAcceleration: number = 0;
  private currentRotationRate: number = 0;
  private listeners: ((data: WebSensorData) => void)[] = [];

  constructor() {
    this.handleMotionEvent = this.handleMotionEvent.bind(this);
  }

  /**
   * Start listening to device motion
   */
  async startListening(): Promise<boolean> {
    if (this.isListening) return true;

    // Check if DeviceMotionEvent exists
    if (typeof DeviceMotionEvent === 'undefined') {
      console.warn('DeviceMotionEvent is not supported on this browser');
      return false;
    }

    // Request permission for iOS 13+
    if (typeof (DeviceMotionEvent as any).requestPermission === 'function') {
      try {
        const permission = await (DeviceMotionEvent as any).requestPermission();
        if (permission !== 'granted') {
          console.warn('Motion sensor permission denied');
          return false;
        }
      } catch (error) {
        console.error('Error requesting motion permission:', error);
        return false;
      }
    }

    window.addEventListener('devicemotion', this.handleMotionEvent, true);
    this.isListening = true;
    return true;
  }

  /**
   * Stop listening
   */
  stopListening(): void {
    if (!this.isListening) return;
    window.removeEventListener('devicemotion', this.handleMotionEvent, true);
    this.isListening = false;
    this.currentAcceleration = 0;
    this.currentRotationRate = 0;
  }

  private handleMotionEvent(event: DeviceMotionEvent): void {
    const acc = event.accelerationIncludingGravity || event.acceleration;
    const rot = event.rotationRate;

    if (acc) {
      const x = acc.x || 0;
      const y = acc.y || 0;
      const z = acc.z || 0;
      // Subtract gravity (approximate) if including gravity
      this.currentAcceleration = Math.sqrt(x * x + y * y + z * z);
    }

    if (rot) {
      const alpha = rot.alpha || 0;
      const beta = rot.beta || 0;
      const gamma = rot.gamma || 0;
      this.currentRotationRate = Math.sqrt(alpha * alpha + beta * beta + gamma * gamma);
    }

    const data: WebSensorData = {
      acceleration: this.currentAcceleration,
      rotationRate: this.currentRotationRate,
      timestamp: Date.now()
    };

    this.listeners.forEach(callback => callback(data));
  }

  /**
   * Add a listener for sensor updates
   */
  addListener(callback: (data: WebSensorData) => void): void {
    this.listeners.push(callback);
  }

  /**
   * Remove a listener
   */
  removeListener(callback: (data: WebSensorData) => void): void {
    this.listeners = this.listeners.filter(l => l !== callback);
  }

  getLatestData(): WebSensorData {
    return {
      acceleration: this.currentAcceleration,
      rotationRate: this.currentRotationRate,
      timestamp: Date.now()
    };
  }
}

export const webSensors = new WebSensorService();
