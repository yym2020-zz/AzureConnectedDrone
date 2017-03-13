package com.drone.cameratime.flight;

import android.app.Dialog;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.afollestad.materialdialogs.MaterialDialog;
import com.microsoft.azure.iothub.DeviceClient;
import com.microsoft.azure.iothub.IotHubClientProtocol;
import com.microsoft.azure.iothub.IotHubEventCallback;
import com.microsoft.azure.iothub.IotHubStatusCode;
import com.microsoft.azure.iothub.Message;
import com.drone.cameratime.MyApplication;
import com.drone.cameratime.R;
import com.drone.cameratime.activity.CameraActivity;
import com.drone.cameratime.bean.EventCenter;
import com.drone.cameratime.bean.FlyControllerEntity;
import com.drone.cameratime.bean.ZOWarningMdel;
import com.drone.cameratime.constants.uav.FlyCmdConstant;
import com.drone.cameratime.constants.uav.ServerLinks;
import com.drone.cameratime.constants.uav.UavConstants;
import com.drone.cameratime.constants.uav.UserDataConstans;
import com.drone.cameratime.dialog.MaterialDialogBuilderL;
import com.drone.cameratime.dialog.TipsPop;
import com.drone.cameratime.manager.UserAndFlyData;
import com.drone.cameratime.net.MyCallback;
import com.drone.cameratime.net.MyOKHttpUtils;
import com.drone.cameratime.utils.CommonUtils;
import com.drone.cameratime.utils.LocationUtil;
import com.drone.cameratime.utils.LogUtil;
import com.drone.cameratime.utils.UavDataPaserUtil;
import com.drone.cameratime.utils.UiUtil;
import com.drone.cameratime.utils.UserUtils;
import com.drone.cameratime.utils.VibratorUtil;
import com.drone.cameratime.utils.WarningUtil;
import com.drone.cameratime.widgets.TakeoffLandDialog;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Call;

/**
 * Updated by yym on 2017/1/13.
 * 特殊飞行和起飞
 * modify:MrLiKH
 */
public class TakeOffAndFly {
  private FlyControllerEntity mFlyInfo;
  private int gpsNum;
  private float uavLng;
  private float uavLat;
  private int flyState;
  private int motorState;//电机状态
  private int flyTimeLong;            //飞行总时间 不关机状态下
  private int mStartFlyTime;          //飞行架次开始时间
  private int mFlyTimeHold;           //本架次飞行时间
  private int backState;//返航状态

  private MyApplication mMyApplication;
  private CameraActivity mCameraActivity;
  private Button mOneKeyTakeOffBtn;
  private ImageView mLandImageView;
  private ImageView mEmergencyHoverImageView;
  private int locationMode;
  private int batteryPercentage;//飞机电量百分比0--100%
  private int isEnableFly;
  private String appVersion = "";
  private String UserId = "";
  private int isOnGroud;

  private String mAzureIoTConnString = "HostName=dobby-iothub-free.azure-devices.cn;DeviceId=XlAndroidDevice;SharedAccessKey=nDHMFfAY1yTDxF/y10cM825SiPJTdMkqWnitcSrLLlE=";
  private DeviceClient mDeviceClient;

  

  public TakeOffAndFly(MyApplication myApplication, CameraActivity cameraActivity,
                       Button oneKeyTakeOff, ImageView land, ImageView emergencyHover) {
    EventBus.getDefault().register(this);
    appVersion = CommonUtils.getVersionName(cameraActivity);
    UserId = UserUtils.getUserID();
    mMyApplication = myApplication;
    mCameraActivity = cameraActivity;
    mOneKeyTakeOffBtn = oneKeyTakeOff;
    mLandImageView = land;
    mEmergencyHoverImageView = emergencyHover;
  }

  public void destroy() {
    EventBus.getDefault().unregister(this);
    MyOKHttpUtils.getIntance().cancelTag(this);
  }

  

  private void initAZureIotDeviceClient() {
    IotHubClientProtocol protocol = IotHubClientProtocol.HTTPS;
    try {
      mDeviceClient = new DeviceClient(mAzureIoTConnString, protocol);
      mDeviceClient.open();
    } catch(IOException e1) {
      LogUtil.e("Exception while opening IoTHub connection: " + e1.toString());
    } catch(Exception e2) {
      LogUtil.e("Exception while opening IoTHub connection: " + e2.toString());
    }
  }


  private void uploadFlyInfoWhenConnUAV() {
    Map<String, String> params = new HashMap<>();
    String uploadData = uploadFlyInfoToWeb();
    params.put("data", uploadData);
    MyOKHttpUtils.getIntance().oKHttpPost(ServerLinks.CONN_UAV_4G_UPLOAD, this, params, new MyCallback() {
      @Override
      public void onError(Call call, Exception e, int id) {
      }

      @Override
      public void onResponse(String response, int id) {

      }
    });

    try
    {
      String uploadFlyInfo2Iot = uploadFlyInfoToIot();
      if(uploadFlyInfo2Iot != null) {
        Message msg = new Message(uploadFlyInfo2Iot);
        msg.setProperty("DobbyFlyState", Integer.toString(flyState));

        LogUtil.d("sending message to IoTHub : " + uploadFlyInfo2Iot);

        EventCallback eventCallback = new EventCallback();
        mDeviceClient.sendEventAsync(msg, eventCallback, flyState);
      }
    } catch (Exception e) {
      LogUtil.e("Exception while sending message to IoTHub : " + e.toString());
    }
  }

  protected static class EventCallback implements IotHubEventCallback {
    public void execute(IotHubStatusCode status, Object context){
      Integer flyState = (Integer) context;
      System.out.println("IoT Hub responded to message "+ flyState.toString()
        + " with status " + status.name());
    }
  }

  

  private String uploadFlyInfoToIot() {
    if(mFlyInfo == null)
      return null;

    JSONObject jsonobj = new JSONObject();
    try {
      jsonobj.put("year", mFlyInfo.year); // 年
      jsonobj.put("month", mFlyInfo.month); // 月
      jsonobj.put("day", mFlyInfo.day); // 日
      jsonobj.put("hour", mFlyInfo.hour); // 时
      jsonobj.put("minute", mFlyInfo.minute); // 分
      jsonobj.put("second", mFlyInfo.second); // 秒
      jsonobj.put("curlongitude", mFlyInfo.uavLng); // 当前经度
      jsonobj.put("curlatitude", mFlyInfo.uavLat); //当前纬度
      jsonobj.put("dstlongitude", mFlyInfo.dstLng); //目标经度
      jsonobj.put("dstlatitude", mFlyInfo); // 目标纬度
      jsonobj.put("gpsnumber", mFlyInfo.gpsNumber); // GPS星数
      jsonobj.put("speedgpsx", mFlyInfo.speedGpsX); // gps velx
      jsonobj.put("speedgpsy", mFlyInfo.speedGpsY); // gps vely
      jsonobj.put("height", mFlyInfo.height); // 无人机离地高度
      jsonobj.put("flyControlVoltage", mFlyInfo.flyControlVoltage); // 飞控电压
      jsonobj.put("batteryPercentage", mFlyInfo.batteryPercentage); // 电量百分比
      jsonobj.put("temperature", mFlyInfo.temperature); // 温度
      jsonobj.put("shakingCoefficient", mFlyInfo.shakingCoefficient); // 晃动系数
      jsonobj.put("shockCoefficient", mFlyInfo.shockCoefficient); // 震动系数
      jsonobj.put("flyState", flyState); // 飞行状态：/ 0：飞行中， 1：地面， 2：起飞中， 3：降落中， 4：悬停中；
    } catch (JSONException e) {
      e.printStackTrace();
    } catch (Exception e) {
      e.printStackTrace();
    }

    return jsonobj.toString();
  }

}
