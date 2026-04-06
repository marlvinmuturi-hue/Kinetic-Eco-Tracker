import React from 'react';
import { ActivityType } from '../types';
import { Footprints, Car, Plane, Armchair, Activity, Zap } from 'lucide-react';

interface ActivityIconProps {
  type: ActivityType;
  className?: string;
  size?: number;
}

export const ActivityIcon: React.FC<ActivityIconProps> = ({ type, className = "", size = 24 }) => {
  switch (type) {
    case ActivityType.WALKING:
      return <Footprints size={size} className={className} />;
    case ActivityType.RUNNING:
      return <Activity size={size} className={className} />;
    case ActivityType.CYCLING:
      return (
        <svg 
          width={size} 
          height={size} 
          viewBox="0 0 24 24" 
          fill="none" 
          stroke="currentColor" 
          strokeWidth="2" 
          strokeLinecap="round" 
          strokeLinejoin="round"
          className={className}
        >
          <circle cx="18.5" cy="17.5" r="3.5"/>
          <circle cx="5.5" cy="17.5" r="3.5"/>
          <circle cx="15" cy="5" r="1"/>
          <path d="M12 17.5V14l-3-3 4-3 2 3h2"/>
        </svg>
      );
    case ActivityType.DRIVING:
      return <Car size={size} className={className} />;
    case ActivityType.ELECTRIC_VEHICLE:
      return <Zap size={size} className={className} />;
    case ActivityType.FLYING:
      return <Plane size={size} className={className} />;
    case ActivityType.IDLE:
    default:
      return <Armchair size={size} className={className} />;
  }
};
