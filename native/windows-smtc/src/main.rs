#![windows_subsystem = "windows"]

use base64::Engine;
use serde::Deserialize;
use std::io::{self, BufRead, Write};
use windows::core::{w, Result, HSTRING};
use windows::Foundation::{TimeSpan, TypedEventHandler};
use windows::Media::Playback::MediaPlayer;
use windows::Media::{
    MediaPlaybackStatus, MediaPlaybackType, PlaybackPositionChangeRequestedEventArgs,
    SystemMediaTransportControls, SystemMediaTransportControlsButton,
    SystemMediaTransportControlsButtonPressedEventArgs,
    SystemMediaTransportControlsTimelineProperties,
};
use windows::Storage::Streams::{
    DataWriter, InMemoryRandomAccessStream, RandomAccessStreamReference,
};
use windows::Win32::System::WinRT::{RoInitialize, RoUninitialize, RO_INIT_MULTITHREADED};
use windows::Win32::UI::Shell::SetCurrentProcessExplicitAppUserModelID;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Snapshot {
    #[serde(default)]
    cover_key: String,
    #[serde(default)]
    cover_data: String,
    title: String,
    artist: String,
    state: String,
    position_ms: i64,
    duration_ms: i64,
    can_control: bool,
    can_skip: bool,
    can_seek: bool,
    can_stop: bool,
}

fn emit(line: &str) {
    let mut out = io::stdout().lock();
    let _ = writeln!(out, "{line}");
    let _ = out.flush();
}

fn time(ms: i64) -> TimeSpan {
    TimeSpan {
        Duration: ms.max(0).saturating_mul(10_000),
    }
}

fn run() -> Result<()> {
    let player = MediaPlayer::new()?;
    player.CommandManager()?.SetIsEnabled(false)?;
    let controls = player.SystemMediaTransportControls()?;
    let updater = controls.DisplayUpdater()?;
    updater.SetType(MediaPlaybackType::Music)?;
    updater.SetAppMediaId(&HSTRING::from("DangoMusic"))?;
    let music = updater.MusicProperties()?;
    let timeline = SystemMediaTransportControlsTimelineProperties::new()?;
    timeline.SetStartTime(time(0))?;
    timeline.SetMinSeekTime(time(0))?;

    let button = controls.ButtonPressed(&TypedEventHandler::<
        SystemMediaTransportControls,
        SystemMediaTransportControlsButtonPressedEventArgs,
    >::new(|_, args| {
        let Some(ref args) = *args else { return Ok(()) };
        let command = match args.Button()? {
            SystemMediaTransportControlsButton::Play => "play",
            SystemMediaTransportControlsButton::Pause => "pause",
            SystemMediaTransportControlsButton::Next => "next",
            SystemMediaTransportControlsButton::Previous => "previous",
            SystemMediaTransportControlsButton::Stop => "stop",
            _ => return Ok(()),
        };
        emit(command);
        Ok(())
    }))?;
    let seek = controls.PlaybackPositionChangeRequested(&TypedEventHandler::<
        SystemMediaTransportControls,
        PlaybackPositionChangeRequestedEventArgs,
    >::new(|_, args| {
        if let Some(ref args) = *args {
            let position = args.RequestedPlaybackPosition()?.Duration.max(0) / 10_000;
            emit(&format!("seek\t{position}"));
        }
        Ok(())
    }))?;

    controls.SetIsEnabled(true)?;
    emit("ready");
    let result = read_snapshots(&controls, &music, &updater, &timeline);
    let _ = controls.SetPlaybackStatus(MediaPlaybackStatus::Closed);
    let _ = controls.SetIsEnabled(false);
    let _ = controls.RemoveButtonPressed(button);
    let _ = controls.RemovePlaybackPositionChangeRequested(seek);
    let _ = player.Close();
    result
}

fn read_snapshots(
    controls: &SystemMediaTransportControls,
    music: &windows::Media::MusicDisplayProperties,
    updater: &windows::Media::SystemMediaTransportControlsDisplayUpdater,
    timeline: &SystemMediaTransportControlsTimelineProperties,
) -> Result<()> {
    let mut previous: Option<Snapshot> = None;
    for line in io::stdin().lock().lines().map_while(io::Result::ok) {
        let Ok(snapshot) = serde_json::from_str::<Snapshot>(&line) else {
            continue;
        };
        let last = previous.as_ref();
        if last.is_none_or(|p| {
            p.title != snapshot.title
                || p.artist != snapshot.artist
                || p.cover_key != snapshot.cover_key
                || p.cover_data != snapshot.cover_data
        }) {
            updater.ClearAll()?;
            updater.SetType(MediaPlaybackType::Music)?;
            updater.SetAppMediaId(&HSTRING::from("DangoMusic"))?;
            music.SetTitle(&HSTRING::from(&snapshot.title))?;
            music.SetArtist(&HSTRING::from(&snapshot.artist))?;
            if !snapshot.cover_data.is_empty() {
                if let Err(error) = set_thumbnail(updater, &snapshot.cover_data) {
                    emit(&format!("error\tCover unavailable: {error}"));
                }
            }
            updater.Update()?;
        }
        if last.is_none_or(|p| {
            p.can_control != snapshot.can_control
                || p.can_skip != snapshot.can_skip
                || p.can_stop != snapshot.can_stop
        }) {
            controls.SetIsPlayEnabled(snapshot.can_control)?;
            controls.SetIsPauseEnabled(snapshot.can_control)?;
            controls.SetIsNextEnabled(snapshot.can_skip)?;
            controls.SetIsPreviousEnabled(snapshot.can_skip)?;
            controls.SetIsStopEnabled(snapshot.can_stop)?;
        }
        if last.is_none_or(|p| p.state != snapshot.state) {
            let status = match snapshot.state.as_str() {
                "playing" => MediaPlaybackStatus::Playing,
                "paused" => MediaPlaybackStatus::Paused,
                _ => MediaPlaybackStatus::Changing,
            };
            controls.SetPlaybackStatus(status)?;
        }
        let duration = snapshot.duration_ms.max(0);
        let position = snapshot.position_ms.clamp(0, duration);
        timeline.SetEndTime(time(duration))?;
        timeline.SetMinSeekTime(time(if snapshot.can_seek { 0 } else { position }))?;
        timeline.SetMaxSeekTime(time(if snapshot.can_seek {
            duration
        } else {
            position
        }))?;
        timeline.SetPosition(time(position))?;
        controls.UpdateTimelineProperties(timeline)?;
        previous = Some(snapshot);
    }
    Ok(())
}

fn set_thumbnail(
    updater: &windows::Media::SystemMediaTransportControlsDisplayUpdater,
    encoded: &str,
) -> Result<()> {
    let Ok(bytes) = base64::engine::general_purpose::STANDARD.decode(encoded) else {
        return Ok(());
    };
    let stream = InMemoryRandomAccessStream::new()?;
    let writer = DataWriter::CreateDataWriter(&stream)?;
    writer.WriteBytes(&bytes)?;
    writer.StoreAsync()?.join()?;
    writer.DetachStream()?;
    stream.Seek(0)?;
    updater.SetThumbnail(&RandomAccessStreamReference::CreateFromStream(&stream)?)?;
    Ok(())
}

fn main() {
    if let Err(error) = unsafe { RoInitialize(RO_INIT_MULTITHREADED) } {
        emit(&format!("error\t{error}"));
        return;
    }
    let _ = unsafe { SetCurrentProcessExplicitAppUserModelID(w!("DangoMusic.Media")) };
    if let Err(error) = run() {
        emit(&format!("error\t{error}"));
    }
    unsafe { RoUninitialize() };
}
