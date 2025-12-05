import React from 'react';
import { ActivityType } from '../types';
import { Footprints, Car, Plane, Armchair } from 'lucide-react';

interface ActivityIconProps {
  type: ActivityType;
  className?: string;
  size?: number;
}

export const ActivityIcon: React.FC<ActivityIconProps> = ({ type, className = "", size = 24 }) => {
  switch (type) {
    case ActivityType.WALKING:
      return <Footprints size={size} className={className} />;
    case ActivityType.DRIVING:
      return <Car size={size} className={className} />;
    case ActivityType.FLYING:
      return <Plane size={size} className={className} />;
    case ActivityType.IDLE:
    default:
      return <Armchair size={size} className={className} />;
  }
};
