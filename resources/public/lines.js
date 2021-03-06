var VelocitySmoothedValue = function(_updateFrequency) {
    var DAMP = 5;
    var updateFrequency = _updateFrequency;
    var target = 0;
    var value = 0;
    var velocity = 0;

    function tick() { 
    value += velocity;
      velocity = (target - value) / DAMP;
    }

    this.getValue = function() {
      return value;
    }

    this.getTarget = function() {
      return target;
    }

    this.setTarget = function(newTarget) {
      target = newTarget;
    }

    window.setInterval(tick, 1 / updateFrequency * 1000);
    return this;
}

// Live windowed average over the given time period.
var TimeWindowAverage = function(periodMs) {
  // Expected maximum per time period. Not a problem if exceeded.
  var EXPECTED_MAX = 10;

  // Ringbuffer of timestamps.
  var ringBufferSize = EXPECTED_MAX * periodMs;
  var ringBuffer = new Float64Array(ringBufferSize);
  var ringI = 0;

  // Don't worry if we over-write, it just means we saturate the counter.
  this.ping = function() {
    ringBuffer[ringI] = new Date().getTime();
    ringI = (ringI + 1) % ringBufferSize;
  }

  // How many ticks within the window?
  this.getValue = function() {
    var then = new Date().getTime() - periodMs;
    
    var value = 0;
    for (var i=0;i < ringBufferSize; i++) {
      if (ringBuffer[i] > then) {
        value++;
      }
    }
    return value;
  }

  return this;
}

// Velocity smoothed tick rate.
var SmoothedRateCounter = function(_updateFrequency, _periodMs) {
  var updateFrequency = _updateFrequency;
  var periodMs = _periodMs;
  var value = new VelocitySmoothedValue(updateFrequency);
  var average = new TimeWindowAverage(periodMs);

  this.ping = function() {
    average.ping();
  }

  this.getValue = function() {
    return value.getValue();
  }

  function tick() {
    value.setTarget(average.getValue());
  }

  // updateFrequency used both to clock smoother and poll to update smoother target value.
  window.setInterval(tick, 1 / updateFrequency * 1000);

  return this;
}

// Appears to be int array, but loses precision toward the end.
// First region has full precision, second region has half etc.
var BASE = 1.01;
var VagueningRateHistory = function(_size) {
  var size = _size;
  var logBase = BASE;
  var allocatedSize = Math.floor(Math.log(size) / Math.log(logBase));

  var values = new Float32Array(allocatedSize);   

  this.getAllocatedSize = function() {
    return allocatedSize;
  }

  this.getValue = function(logicalIndex) {
    var offset = Math.floor(Math.log(logicalIndex) / Math.log(logBase));
    var multipler = Math.pow(logBase, offset);

    return values[offset] / multipler;
  }

  this.getMaxValue = function() {
    var maxValue = 0;
    for (var i = 0; i < allocatedSize; i++) {
      var numRepresented = Math.pow(logBase, i);
      for (var j=0; j < numRepresented; j++) {
        maxValue = Math.max(maxValue, values[i] / numRepresented);
      }
    }
    return maxValue;
  }

  // Optionally push a value on the end and shift down by one logical value.
  // This is the only way data gets in!
  this.shift = function(value) {
    maxValue = 0;
    for (var i=allocatedSize-1;i>1;i--) {

      // If we borrow zero we'll get a divide-by-zero, and in that case there's nothing to shift on.
      var borrowedValue = values[i-1] / Math.pow(logBase, (i-1));
      values[i-1] -= borrowedValue;

      if (!isNaN(borrowedValue)) {
        values[i] += borrowedValue;
        maxValue = Math.max(values[i]||0, maxValue / Math.pow(logBase, i));
      }
    }

    values[1] = value || 0;
  }

  // Iterate in the logical value domain; don't interpolate x values.
  this.iterateLogical = function(callback) {
    var x = 0;
    for (var i = 0; i < allocatedSize; i++) {
      var numRepresented = Math.pow(logBase, i);
      callback(x, values[i] / numRepresented);
      x += numRepresented;
    } 
  }

  // Iterate in the logarithmic value domain; don't interpolate x values.
  this.iterateLogarithmic = function(callback) {
    var x = 0;
    for (var i = 0; i < allocatedSize; i++) {
      var numRepresented = Math.pow(logBase ,i);
      callback(i, values[i] / numRepresented);
      x += numRepresented;
    } 
  }

  return this;
}

// A tone on the whole-tone-scale that's modulated by a value.
var AwfulNoise = function(_i, _bus, _audio) {
  var value = 0;
  var i = _i;
  var bus = _bus;
  var audio = _audio;

  // Frequency for note i plus modulation.
  function freqFor(i, modulation) {
    return Math.pow(2, ((((i + 1) * 2 + Math.sqrt(modulation)))/12)) * 110;
  }

  var sourceGain = audio.createGain();

  sourceGain.connect(bus);
  sourceGain.gain.value = 0.1;
  var oscillator = audio.createOscillator();
  oscillator.type = 'triangle';
  
  oscillator.connect(sourceGain);
  oscillator.gain = 0.1;
  oscillator.start();

  this.setValue = function(_value) {
    value = _value;
    oscillator.frequency.value = freqFor(i, value);
  }

  return this;
}

// Plot the rate of a number of things, and the history.
var MultipleRatePlotter = function(_canvas, _tickHz, _countWindowMsecs,
                   _historySize) {
  var canvas = _canvas;
  var tickHz = _tickHz;
  var countWindowMsecs = _countWindowMsecs;
  var historySize = _historySize;

  // Keep a max value for scaling. But smooth it out.
  var maxValue = new VelocitySmoothedValue(tickHz);

  // Keep keys into things so they can be added as we find out about them without disrupting the visual order.
  var things = {};
  var thingNames = [];

  // Audio
  var audio = new (window.AudioContext || window.webkitAudioContext)();
  var gain = audio.createGain();
  gain.connect(audio.destination);
  gain.gain.setValueAtTime(0.05, 0);

  var delay = audio.createDelay();
  delay.delayTime.value = 0.5;

  var feedback = audio.createGain();
  feedback.gain.value = 0.1;

  delay.connect(feedback);
  feedback.connect(delay);

  delay.connect(gain);

  var bus = audio.createGain();
  bus.connect(delay);
  bus.connect(gain);

  // Graphics
  var context = canvas.getContext("2d");

  // 2 on retina, 1 normal.
  var ratio = window.devicePixelRatio;

  context.scale(ratio,ratio);
  canvas.style.width = width + "px";
  canvas.style.height = height + "px";

  var width;
  var height;

  function resize() {
    width = window.innerWidth;
    height = window.innerHeight;
    canvas.style.width = width + "px";
    canvas.style.height = height + "px";
    canvas.width = width * ratio;
    canvas.height = height * ratio;
  }
  resize();
  window.addEventListener("resize", resize);

  // Get or create named thing.
  function getThing(thingName) {
    var thing = things[thingName];
    if (!thing) {
      thing = {history: new VagueningRateHistory(historySize),
               counter: new SmoothedRateCounter(tickHz, _countWindowMsecs),
               awfulNoise: new AwfulNoise(thingNames.length, bus, audio),
               mostRecentData: ""};
      thingNames.push(thingName);
      things[thingName] = thing;
    }
    return thing;
  }

  this.ping = function(thingName, thingData) {
    var thing = getThing(thingName);

    thing.counter.ping();
    thing.mostRecentData = thingData.split("\n");
  }

  function render() {
    canvas.width = canvas.width;

    if (thingNames.length == 0) {
      context.fillStyle = "#101010";
      context.font = "40px sans-serif"
      context.fillText("Waiting for something to happen...",
              Math.floor(width / 10),
              Math.floor(height / 4));
      return
    }

    var LANE_MARGIN = 5;
    var laneHeight = height / thingNames.length - LANE_MARGIN;

    var laneSizeOverall = laneHeight + LANE_MARGIN;

    // Find the current maximum of all things so we can scale all by that.
    // Update smoothed value to converage on that so we don't get jerky re-scaling.
    var thisMaxValue = 0;
    for (var thingI = 0; thingI < thingNames.length; thingI++) {
      var thingName = thingNames[thingI];
      var thing = things[thingName];
      thisMaxValue = Math.max(thisMaxValue, thing.history.getMaxValue());
    }
    maxValue.setTarget(thisMaxValue);

    for (var thingI = 0; thingI < thingNames.length; thingI++) {
      var thingName = thingNames[thingI];
      var thing = things[thingName];

      thing.awfulNoise.setValue(thing.counter.getValue());

      var laneOffset = thingI * laneSizeOverall;

      // Main display is log scale in X dimension.
      var xLogScale = width / thing.history.getAllocatedSize();
      var yLogScale = laneHeight / Math.max(maxValue.getValue(), 1);

      // Supplementary is linear and smaller.
      var yScale = laneHeight / Math.max(maxValue.getValue(), 1) * 0.1;
      var xScale = width / historySize;

      // Background
      context.fillStyle = "#f0f0f0";
      context.fillRect(0 * ratio,
                       thingI * laneSizeOverall * ratio,
                       width * ratio,
                       (laneSizeOverall - LANE_MARGIN) * ratio)

      // Value tracker
      context.fillStyle = "#e0e0e0";
      context.fillRect(0 * ratio,
                       (Math.floor(laneHeight - (thing.counter.getValue()*yLogScale-2) + laneOffset)) * ratio,
                       width * ratio,
                       4)

      // Text
      context.fillStyle = "#a0a0a0";
      // Don't let text get so big it doesn't fit width-ways.
      // Or too small neither.
      context.font = "" + (Math.min(laneHeight/2, 100) * ratio) + "px sans-serif";
      context.fillText(thingName + " " + Math.floor(thing.counter.getValue()*60) + " per minute",
              LANE_MARGIN * ratio,
              (thingI * laneSizeOverall + (laneSizeOverall/2)) * ratio);

      // Draw event.
      var EXPECTED_LINES = 30;
      var EXPECTED_CHARS = 70;
      var textSize = Math.floor(laneSizeOverall / EXPECTED_LINES) * ratio; // expect approx this many lines.
      context.font = "" + textSize + "px sans-serif";
      for (var li=0; li < Math.min(thing.mostRecentData.length, EXPECTED_LINES); li++) {
        context.fillText(thing.mostRecentData[li],
                         width - (EXPECTED_CHARS * textSize) * ratio,
                         ((thingI * laneSizeOverall) + (LANE_MARGIN * 2) + (li * textSize)) * ratio);
      }
      
      // Draw linear-time small display.
      context.strokeStyle = "#b0b0b0";
      context.lineWidth = 1;
      context.beginPath();
      var start = true;
      thing.history.iterateLogical(function(x, y) {
        var backwardX = width - Math.floor(x * xScale);
        var upsideDownY = (laneHeight * 0.1) - Math.floor(y * yScale);
        if (!start) {
          context.moveTo(backwardX * ratio,(upsideDownY + laneOffset) * ratio);
          start = false;
        } else {
          context.lineTo(backwardX * ratio,(upsideDownY + laneOffset) * ratio);
        }
      });
      context.stroke();

      // Draw main logarithmic time display.
      context.strokeStyle = "#303030";
      context.lineWidth = 2;
      context.beginPath();
      var start = true;
      
      thing.history.iterateLogarithmic(function(x, y) {
        var backwardX = width - Math.floor(x * xLogScale);
        var upsideDownY = laneHeight - Math.floor(y * yLogScale);
        
        if (!start) {
          context.moveTo(backwardX * ratio,
                         (upsideDownY + laneOffset) * ratio);
          start = false;
        } else {
          context.lineTo(backwardX * ratio,
                         (upsideDownY + laneOffset) * ratio);
        }
        
      });
      context.stroke();
    }
  }
  
  function tick() {
    // Shift the current value of each thing into its history.
    for (var i = 0; i < thingNames.length; i++) {
      things[thingNames[i]].history.shift(things[thingNames[i]].counter.getValue());
    }

    render();
  }

  window.setInterval(tick, 1000 / tickHz);
}
