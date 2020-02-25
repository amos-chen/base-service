import React from 'react';
import ReactEchartsCore from 'echarts-for-react/lib/core';
import echarts from 'echarts';

const Charts = () => {
  const getOption = () => ({
    grid: {
      top: '30px',
      left: 0,
      right: '50px',
      bottom: 0,
      containLabel: true,
    },
    tooltip: {
      trigger: 'axis',
      position(pt) {
        return [pt[0], '10%'];
      },
    },
    xAxis: {
      type: 'category',
      data: ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'],
      name: '时间',
      nameTextStyle: {
        color: 'rgba(0,0,0,1)',
        fontSize: '13px',
      },
      splitLine: {
        show: true,
      },
      axisLabel: { color: 'rgba(0,0,0,0.65)' },
      axisLine: {
        lineStyle: {
          color: '#EEEEEE',
        },
      },
      axisTick: {
        alignWithLabel: true,
      },
    },
    yAxis: {
      nameTextStyle: {
        color: 'rgba(0,0,0,1)',
        fontSize: '13px',
      },
      name: '人数',
      type: 'value',
      axisLabel: { color: 'rgba(0,0,0,0.65)' },
      axisLine: {
        lineStyle: {
          color: '#EEEEEE',
        },
      },
    },
    series: [{
      data: [820, 932, 901, 934, 1290, 1330, 1320],
      type: 'line',
      smooth: true,
      symbol: 'circle',
      areaStyle: {
        normal: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{
            offset: 0,
            color: 'rgba(80, 107, 255, 0.3)',
          }, {
            offset: 1,
            color: 'rgba(82, 102, 212, 0)',
          }]),
        },
      },
      itemStyle: {
        normal: {
          color: '#5266D4', // 改变折线点的颜色
          // lineStyle:{
          //   color:'#8cd5c2' //改变折线颜色
          // }
        },
      },
    }],
  });
  return (
    <ReactEchartsCore
      echarts={echarts}
      option={getOption()}
      notMerge
      style={{
        width: '100%',
        height: 216,
      }}
      lazyUpdate
    />
  );
};

export default Charts;
