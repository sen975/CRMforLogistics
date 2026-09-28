import React from "react";
import { Composition } from "remotion";
import { PitchVideo } from "./PitchVideo";

const FPS = 30;
const TOTAL_SECONDS = 216; // 3:36
const WIDTH = 1920;
const HEIGHT = 1080;

export const Root: React.FC = () => {
  return (
    <>
      <Composition
        id="PitchVideo"
        component={PitchVideo}
        durationInFrames={TOTAL_SECONDS * FPS}
        fps={FPS}
        width={WIDTH}
        height={HEIGHT}
      />
    </>
  );
};