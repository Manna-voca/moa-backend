package com.hanamja.moa.api.service.group;

import com.hanamja.moa.api.controller.group.SortedBy;
import com.hanamja.moa.api.dto.comment.request.WritingCommentRequestDto;
import com.hanamja.moa.api.dto.comment.response.CommentInfoResponseDto;
import com.hanamja.moa.api.dto.group.request.KickOutRequestDto;
import com.hanamja.moa.api.dto.group.request.MakingGroupRequestDto;
import com.hanamja.moa.api.dto.group.request.ModifyingGroupRequestDto;
import com.hanamja.moa.api.dto.group.response.*;
import com.hanamja.moa.api.dto.util.DataResponseDto;
import com.hanamja.moa.api.entity.album.Album;
import com.hanamja.moa.api.entity.album.AlbumRepository;
import com.hanamja.moa.api.entity.comment.Comment;
import com.hanamja.moa.api.entity.comment.CommentRepository;
import com.hanamja.moa.api.entity.group.Group;
import com.hanamja.moa.api.entity.group.GroupRepository;
import com.hanamja.moa.api.entity.group.State;
import com.hanamja.moa.api.entity.group_hashtag.GroupHashtag;
import com.hanamja.moa.api.entity.group_hashtag.GroupHashtagRepository;
import com.hanamja.moa.api.entity.hashtag.Hashtag;
import com.hanamja.moa.api.entity.hashtag.HashtagRepository;
import com.hanamja.moa.api.entity.notification.Notification;
import com.hanamja.moa.api.entity.notification.NotificationRepository;
import com.hanamja.moa.api.entity.point_history.PointHistory;
import com.hanamja.moa.api.entity.point_history.PointHistoryRepository;
import com.hanamja.moa.api.entity.user.User;
import com.hanamja.moa.api.entity.user.UserAccount.UserAccount;
import com.hanamja.moa.api.entity.user.UserRepository;
import com.hanamja.moa.api.entity.user_group.UserGroup;
import com.hanamja.moa.api.entity.user_group.UserGroupRepository;
import com.hanamja.moa.exception.custom.InvalidMaxPeopleNumberException;
import com.hanamja.moa.exception.custom.InvalidParameterException;
import com.hanamja.moa.exception.custom.NotFoundException;
import com.hanamja.moa.exception.custom.UserInputException;
import com.hanamja.moa.utils.s3.AmazonS3Uploader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
@Slf4j
public class GroupService {
    private final AlbumRepository albumRepository;
    private final UserRepository userRepository;
    private final UserGroupRepository userGroupRepository;
    private final GroupRepository groupRepository;
    private final GroupHashtagRepository groupHashtagRepository;
    private final HashtagRepository hashtagRepository;
    private final NotificationRepository notificationRepository;
    private final PointHistoryRepository pointHistoryRepository;
    private final CommentRepository commentRepository;
    private final AmazonS3Uploader amazonS3Uploader;

    @Transactional
    public GroupInfoResponseDto makeNewGroup(UserAccount userAccount, MakingGroupRequestDto makingGroupRequestDto) {

        User user = validateUser(userAccount.getUserId());

        // Hashtag #?????? ?????? ??? ??????, ?????? (?????? ???????????? ?????? ??????)
        List<Hashtag> hashtagList = saveHashtags(makingGroupRequestDto.getHashtags());

        Group newGroup = MakingGroupRequestDto.toEntity(makingGroupRequestDto, user);
        groupRepository.save(newGroup);

        UserGroup userGroup = UserGroup
                .builder()
                .progress("")
                .joiner(user)
                .group(newGroup)
                .build();

        userGroupRepository.save(userGroup);
        // GroupHashtag ?????? ??? Group?????? ?????? ??????, ??????
        hashtagList.stream().map(
                x -> GroupHashtag
                        .builder()
                        .group(newGroup)
                        .hashtag(x)
                        .build()
        ).forEach(groupHashtagRepository::save);

        return GroupInfoResponseDto.from(newGroup, hashtagList.stream().map(Hashtag::getName).collect(Collectors.toList()));
    }

    @Transactional
    public GroupInfoResponseDto modifyExistingGroup(UserAccount userAccount, ModifyingGroupRequestDto modifyingGroupRequestDto) {
        User user = validateUser(userAccount.getUserId());

        // ??????????????? ??????
//        validateSenior(user);

        // groupId??? group ????????????
        Group existingGroup = groupRepository.findById(modifyingGroupRequestDto.getId()).orElseThrow(
                () -> NotFoundException
                        .builder()
                        .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .message("???????????? ?????? groupId?????????.")
                        .build()
        );

        validateMaker(user, existingGroup);

        if (existingGroup.getCurrentPeopleNum() > modifyingGroupRequestDto.getMaxPeopleNum()) {
            throw InvalidMaxPeopleNumberException
                    .builder()
                    .httpStatus(HttpStatus.BAD_REQUEST)
                    .message("????????? ????????? ?????? ????????? ?????? ?????? ??? ????????????.")
                    .build();
        }

        // group ?????? ??????
        existingGroup.modifyGroupInfo(
                modifyingGroupRequestDto.getName(),
                modifyingGroupRequestDto.getDescription(),
                modifyingGroupRequestDto.getMeetingAt(),
                modifyingGroupRequestDto.getMaxPeopleNum()
        );

        // ?????? ???????????? ?????? ??????
        groupHashtagRepository.deleteAllByGroup_Id(existingGroup.getId());

        // ????????? ???????????? ?????? ?????? ??? ?????? ???????????? ??????
        List<Hashtag> hashtagList = saveHashtags(modifyingGroupRequestDto.getHashtags());
        hashtagList.stream().map(
                x -> GroupHashtag
                        .builder()
                        .group(existingGroup)
                        .hashtag(x)
                        .build()
        ).forEach(groupHashtagRepository::save);

        // ????????? group ?????? ??????
        return GroupInfoResponseDto.from(existingGroup, hashtagList.stream().map(Hashtag::getName).collect(Collectors.toList()));
    }

    @Transactional
    public GroupInfoResponseDto removeExistingGroup(UserAccount userAccount, Long groupId) {
        User user = validateUser(userAccount.getUserId());

        // ??????????????? ??????
//        validateSenior(user);

        // groupId??? group ????????????
        Group existingGroup = groupRepository.findById(groupId).orElseThrow(
                () -> NotFoundException
                        .builder()
                        .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .message("????????? ???????????????. ?????? ??????????????????.")
                        .build()
        );

        List<String> hashtagStringList = getHashtagStringList(existingGroup);

        GroupInfoResponseDto removedGroupDto =
                GroupInfoResponseDto.from(existingGroup, hashtagStringList);


        // UserGroup, GroupHashtag?????? ?????? Group ?????? ??????
        userGroupRepository.deleteAllByGroup_Id(groupId);
        groupHashtagRepository.deleteAllByGroup_Id(groupId);

        // Group ??????
        groupRepository.deleteById(groupId);

        return removedGroupDto;
    }

    private User validateUser(Long userId) {
        return userRepository.findById(userId).orElseThrow(
                () -> NotFoundException
                        .builder()
                        .httpStatus(HttpStatus.BAD_REQUEST)
                        .message("???????????? ?????? ??????????????????.")
                        .build()
        );
    }

    private Group validateGroup(Long groupId) {
        return groupRepository.findById(groupId).orElseThrow(
                () -> NotFoundException
                        .builder()
                        .httpStatus(HttpStatus.BAD_REQUEST)
                        .message("???????????? ?????? ???????????????.")
                        .build()
        );
    }

    private void validateSenior(User user) {
        if (user.isFreshman()) {
            log.info("???????????? ?????? ?????? ?????? ?????? - ??????: {}, ??????: {}", user.getStudentId(), user.getName());
            throw UserInputException
                    .builder()
                    .httpStatus(HttpStatus.UNAUTHORIZED)
                    .message("???????????? ????????? ????????? ??? ????????????.")
                    .build();
        }
    }

    private void validateMaker(User user, Group group) {
        if (!group.getMaker().getId().equals(user.getId())) {
            throw UserInputException
                    .builder()
                    .httpStatus(HttpStatus.UNAUTHORIZED)
                    .message("?????? ????????? ????????? ????????? ????????????.")
                    .build();
        }
    }

    private Comment validateComment(Long commentId) {
        return commentRepository.findById(commentId).orElseThrow(
                () -> NotFoundException
                        .builder()
                        .httpStatus(HttpStatus.NOT_FOUND)
                        .message("???????????? ?????? ???????????????.")
                        .build()
        );
    }

    private List<String> getHashtagStringList(Group existingGroup) {
        return groupHashtagRepository.findAllByGroup_Id(existingGroup.getId())
                .stream().map(x -> hashtagRepository.findById(x.getHashtag().getId()))
                .map(x -> x.orElseThrow().getName()).collect(Collectors.toList());
    }

    @Transactional
    protected List<Hashtag> saveHashtags(String hashtagString) {
        List<String> hashtagStringList = hashtagString != null ? new ArrayList<>(List.of(hashtagString.split("#"))) : new ArrayList<>();
        if (hashtagStringList.size() > 0) {
            hashtagStringList.remove(0);
        }

        return hashtagStringList.stream().map(
                x -> {
                    if (hashtagRepository.existsByName(x)) {
                        // ?????? ?????? ????????? ??????????????? ???????????? ?????? ?????? ?????? ????????????
                        Hashtag existingHashtag = hashtagRepository.findByName(x).orElseThrow();
                        existingHashtag.updateTouchedAt();

                        // ????????? ??????????????? ??????
                        return hashtagRepository.findByName(x).orElseThrow(
                                () -> NotFoundException
                                        .builder()
                                        .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                                        .message("???????????? ???????????????. ?????? ??????????????????.")
                                        .build()
                        );
                    } else {
                        // ?????? ????????? ??????????????? ???????????? ?????? ?????? ????????? ???????????? ??????
                        return hashtagRepository.save(
                                Hashtag
                                        .builder()
                                        .name(x)
                                        .build()
                        );
                    }
                }
        ).collect(Collectors.toList());
    }

    public DataResponseDto<List<GroupInfoResponseDto>> getExistingGroups(SortedBy sortedBy) {
        if (sortedBy == SortedBy.RECENT) {
            List<GroupInfoResponseDto> resultDtoList = groupRepository
                    .findExistingGroupsByRECENT(State.RECRUITING, LocalDateTime.now())
                    .stream().map(x -> GroupInfoResponseDto.from(x, getHashtagStringList(x)))
                    .collect(Collectors.toList());
            return DataResponseDto.<List<GroupInfoResponseDto>>builder()
                    .data(resultDtoList).build();
        } else if (sortedBy == SortedBy.SOON) {
            List<GroupInfoResponseDto> resultDtoList = groupRepository
                    .findAllByStateAndMeetingAtAfterOrderByMeetingAtAscCreatedAtDesc(State.RECRUITING, LocalDateTime.now())
                    .stream().map(x -> GroupInfoResponseDto.from(x, getHashtagStringList(x)))
                    .collect(Collectors.toList());
            resultDtoList.addAll(groupRepository
                    .findAllByStateAndMeetingAtOrderByCreatedAtDesc(State.RECRUITING, null)
                    .stream().map(x -> GroupInfoResponseDto.from(x, getHashtagStringList(x)))
                    .collect(Collectors.toList()));
            return DataResponseDto.<List<GroupInfoResponseDto>>builder()
                    .data(resultDtoList).build();
        } else if (sortedBy == SortedBy.PAST) {
            List<GroupInfoResponseDto> resultDtoList = groupRepository
                    .findAllByStateAndMeetingAtBeforeOrderByCreatedAtDesc(State.DONE, LocalDateTime.now())
                    .stream().map(x -> GroupInfoResponseDto.from(x, getHashtagStringList(x)))
                    .collect(Collectors.toList());
            return DataResponseDto.<List<GroupInfoResponseDto>>builder()
                    .data(resultDtoList).build();
        } else {
            throw InvalidParameterException
                    .builder()
                    .httpStatus(HttpStatus.BAD_REQUEST)
                    .message("???????????? ?????? Query String?????????.")
                    .build();
        }
    }

    public GroupDetailInfoResponseDto getExistingGroupDetail(UserAccount userAccount, Long groupId) {
        User user = validateUser(userAccount.getUserId());

        Group existingGroup = groupRepository.findById(groupId).orElseThrow(
                () -> NotFoundException
                        .builder()
                        .httpStatus(HttpStatus.BAD_REQUEST)
                        .message("groupId??? group??? ?????? ??? ????????????.")
                        .build()
        );

        // ??????????????? ????????? ????????? ??????
        List<SimpleUserInfoResponseDto> simpleUserInfoDtoList =
                userGroupRepository.findAllByGroup_Id(groupId).stream()
                        .map(x -> SimpleUserInfoResponseDto.from(x.getJoiner()))
                        .collect(Collectors.toList());

        // ????????? ??????
        int point = 0;

        // ?????? ?????? 3???????????? ?????? ?????? - 300, 400, 500
        switch (userGroupRepository.countAllByJoiner_IdAndGroup_State(user.getId(), State.DONE)) {
            case 0:
                point = 300;
                break;
            case 1:
                point = 400;
                break;
            case 2:
                point = 500;
                break;
        }

        // ?????? ???????????? ??? ????????? ????????? ???????????? 50, ????????? 100
        for (var x : simpleUserInfoDtoList) {
            if (x.getId().equals(user.getId())) {
                continue;
            }
            if (albumRepository.existsByOwner_IdAndMetUser_Id(user.getId(), x.getId())) {
                point += 50;
            } else if (!user.getId().equals(x.getId())) {
                point += 100;
            }
        }

        return GroupDetailInfoResponseDto.from(existingGroup, getHashtagStringList(existingGroup), simpleUserInfoDtoList, point, getRecentCommentDtoFromGroup(existingGroup));
    }

    public GroupDetailInfoResponseDto getPublicExistingGroupDetail(Long groupId) {
        Group existingGroup = groupRepository.findById(groupId).orElseThrow(
                () -> NotFoundException
                        .builder()
                        .httpStatus(HttpStatus.BAD_REQUEST)
                        .message("groupId??? group??? ?????? ??? ????????????.")
                        .build()
        );

        // ??????????????? ????????? ????????? ??????
        List<SimpleUserInfoResponseDto> simpleUserInfoDtoList =
                userGroupRepository.findAllByGroup_Id(groupId).stream()
                        .map(x -> SimpleUserInfoResponseDto.from(x.getJoiner()))
                        .collect(Collectors.toList());

        return GroupDetailInfoResponseDto.from(existingGroup, getHashtagStringList(existingGroup), simpleUserInfoDtoList, getRecentCommentDtoFromGroup(existingGroup));
    }
        
    public GroupInfoResponseDto join(Long groupId, UserAccount userAccount) {

        Group group = groupRepository.findById(groupId).orElseThrow(
                () -> new NotFoundException(HttpStatus.BAD_REQUEST, "?????? ????????? ?????? ??? ????????????.")
        );

        User user = userRepository.findUserById(userAccount.getUserId())
                .orElseThrow(() -> NotFoundException.builder()
                        .httpStatus(HttpStatus.NOT_FOUND)
                        .message("?????? ????????? ?????? ??? ????????????.")
                        .build());

        User groupMaker = group.getMaker();
        String groupMakerName = groupMaker.getName();
        String groupName = group.getName();

        if (userGroupRepository.existsByGroupIdAndJoinerId(groupId, userAccount.getUserId())) {
            throw UserInputException.builder().httpStatus(HttpStatus.BAD_REQUEST).message("?????? ????????? ???????????????.").build();
        }

        if (group.getState() == State.RECRUITED || group.getState() == State.DONE) {
            throw UserInputException.builder().httpStatus(HttpStatus.BAD_REQUEST).message("????????? ????????? ???????????????.").build();
        }

        if (group.getMeetingAt() != null && group.getMeetingAt().isBefore(LocalDateTime.now())) {
            throw UserInputException.builder().httpStatus(HttpStatus.BAD_REQUEST).message("????????? ?????? ????????? ???????????????.").build();
        }

        if (group.getMaxPeopleNum() <= group.getUserGroupList().size()) {
            throw UserInputException.builder().httpStatus(HttpStatus.BAD_REQUEST).message("??????????????? ????????? ???????????????.").build();
        }
        UserGroup userGroup = UserGroup
                .builder()
                .progress("")
                .joiner(user)
                .group(group)
                .build();
        userGroupRepository.save(userGroup);

        Long currNum = Long.valueOf(userGroupRepository.findAllByGroup_Id(groupId).size());

        // ?????? ????????? ??? ?????? ??? ?????? ??????
        if (currNum.equals(group.getMaxPeopleNum())) {
            userGroupRepository.findAllByGroup_Id(group.getId()).stream()
                    .map(UserGroup::getJoiner)
                    .forEach(joiner -> {
                        notificationRepository.save(
                                Notification
                                        .builder()
                                        .sender(groupMaker)
                                        .receiver(joiner)
                                        .content(groupName + " ????????? ????????? ??? ?????????. ?????? ?????? ????????????????")
                                        .reason("?????? ?????????: " + groupMakerName + "???")
                                        .build()
                        );

                        joiner.notifyUser();
                        userRepository.save(joiner);
                    });
        }
        group.updateCurrentPeopleNum(currNum);
        groupRepository.save(group);

        return GroupInfoResponseDto.from(group, getHashtagStringList(group));
    }

    public DataResponseDto<List<GroupStateInfoResponseDto>> getMyGroupList(Long userId) {
        List<GroupStateInfoResponseDto> dto = new ArrayList<>();

        Arrays.stream(State.values()).collect(Collectors.toList())
                        .stream()
                        .forEach(state -> {
                            List<GroupInfoResponseDto> groupInfos = groupRepository.findAllJoinGroupByUserId(userId, state).stream()
                                    .map(group -> GroupInfoResponseDto.from(group, getHashtagStringList(group)))
                                    .collect(Collectors.toList());

                            dto.add(GroupStateInfoResponseDto.builder()
                                            .state(state).groups(groupInfos)
                                            .build());
                        });
        return DataResponseDto.<List<GroupStateInfoResponseDto>>builder()
                .data(dto)
                .build();
    }

    @Transactional
    public GroupInfoResponseDto quit(Long groupId, UserAccount userAccount) {

        UserGroup userGroup = userGroupRepository.findByGroupIdAndJoinerId(groupId, userAccount.getUserId())
                .orElseThrow(() -> new NotFoundException(HttpStatus.BAD_REQUEST, "?????? ????????? ?????? ??? ????????????."));

        Group group = userGroup.getGroup();
        if(group.getState().equals(State.RECRUITING)){
            group.subtractCurrentPeopleNum();
            userGroupRepository.delete(userGroup);
            groupRepository.save(group);
        } else {
            throw UserInputException.builder()
                    .httpStatus(HttpStatus.BAD_REQUEST)
                    .message("????????? ?????? ?????? ?????????????????????.")
                    .build();
        }

        return GroupInfoResponseDto.from(group, getHashtagStringList(group));
    }

    public void validateGroupMaker(Long userId, Long groupId){
        Group group = groupRepository.findById(groupId)
                .orElseThrow(
                        () -> NotFoundException.builder()
                                .httpStatus(HttpStatus.BAD_REQUEST)
                                .message("groupId??? group??? ?????? ??? ????????????.")
                                .build());

        if (!group.getMaker().getId().equals(userId)) {
            throw NotFoundException.builder()
                    .httpStatus(HttpStatus.UNAUTHORIZED)
                    .message("Group ???????????? ????????????.")
                    .build();
        }
    }

    @Transactional(readOnly = true)
    public GroupInfoListResponseDto getGroupListMadeByMe(Long userId){
        List<GroupInfoResponseDto> response = groupRepository.findAllByMaker_Id(userId).stream()
                .map(group -> GroupInfoResponseDto.from(group, getHashtagStringList(group)))
                .collect(Collectors.toList());
        return GroupInfoListResponseDto.of(response);
    }

    @Transactional
    public GroupInfoResponseDto kickOutMemberFromGroup(Long uid, KickOutRequestDto kickOutRequestDto) {
        User existingUser = validateUser(uid);
        validateGroupMaker(uid, kickOutRequestDto.getGroupId());
        Group existingGroup = groupRepository.findById(kickOutRequestDto.getGroupId()).orElseThrow();

        kickOutRequestDto.getUserList()
                .forEach(userId -> {
                            userGroupRepository.deleteUserGroupByGroup_IdAndJoiner_Id(kickOutRequestDto.getGroupId(), userId);

                            // ??? ???????????? ?????? ?????????
                            User receiver = userRepository.findById(userId).orElseThrow(
                                    () -> NotFoundException.builder()
                                            .httpStatus(HttpStatus.BAD_REQUEST)
                                            .message("userId??? user??? ?????? ??? ????????????.")
                                            .build()
                            );

                            notificationRepository.save(
                                    Notification
                                            .builder()
                                            .sender(existingUser)
                                            .receiver(receiver)
                                            .content(existingUser.getName() + "?????? '" + existingGroup.getName() + "'?????? " + receiver.getName() + "?????? ???????????????.")
                                            .reason(kickOutRequestDto.getReason())
                                            .build()
                            );

                    receiver.notifyUser();
                    userRepository.save(receiver);
                        }
                );

        Long currNum = Long.valueOf(userGroupRepository.findAllByGroup_Id(kickOutRequestDto.getGroupId()).size());
        existingGroup.updateCurrentPeopleNum(currNum);

        return GroupInfoResponseDto.from(existingGroup, getHashtagStringList(existingGroup));
    }

    @Transactional
    public GroupInfoResponseDto cancelGroup(Long uid, Long gid) {
        User groupMaker = validateUser(uid);
        validateGroupMaker(uid, gid);
        Group existingGroup = groupRepository.findById(gid).orElseThrow();

        // ?????? ?????? ?????? ?????????
        userGroupRepository.findAllByGroup_Id(gid).stream()
                .map(UserGroup::getJoiner)
                .forEach(joiner -> {
                    notificationRepository.save(
                            Notification
                                    .builder()
                                    .sender(groupMaker)
                                    .receiver(joiner)
                                    .content(groupMaker.getName() + "?????? '" + existingGroup.getName() + "'??? ???????????????.")
                                    .reason("?????? ?????????: " + groupMaker.getName() + "???")
                                    .build()
                    );

                    joiner.notifyUser();
                    userRepository.save(joiner);
                });
        List<String> hashtagStringList = getHashtagStringList(existingGroup);

        commentRepository.deleteAllByGroup_Id(gid);
        groupHashtagRepository.deleteAllByGroup_Id(gid);
        userGroupRepository.deleteAllByGroup_Id(gid);
        groupRepository.deleteById(gid);

        return GroupInfoResponseDto.from(existingGroup, hashtagStringList);
    }

    @Transactional
    public void groupRecruitDone(Long uid, Long gid){
        validateGroupMaker(uid, gid);
        Group existingGroup = groupRepository.findById(gid).orElseThrow(
                () ->
                        NotFoundException.builder()
                                .httpStatus(HttpStatus.NOT_FOUND)
                                .message("groupId??? ????????? ?????? ??? ????????????.")
                                .build()
        );

        String groupMakerName = existingGroup.getMaker().getName();

        if (existingGroup.getState() != State.RECRUITING) {
            throw NotFoundException.builder()
                    .httpStatus(HttpStatus.CONFLICT)
                    .message("???????????? ????????? ????????????. ????????? ????????? ?????? ??? ?????? ??????????????????.")
                    .build();
        }

        groupRepository.updateGroupState(State.RECRUITED, gid);
        List<User> joinerList = userGroupRepository.findAllByGroup_Id(gid).stream()
                .map(UserGroup::getJoiner).collect(Collectors.toList());

        joinerList.forEach(joiner -> {
            notificationRepository
                    .save(
                            Notification
                                    .builder()
                                    .sender(existingGroup.getMaker())
                                    .receiver(joiner)
                                    .content(existingGroup.getName() + " ????????? ????????? ??????????????????. ??????????????? ??????????????????!")
                                    .reason("?????? ?????????: " + groupMakerName + "???")
                                    .build()
                    );
            joiner.notifyUser();
            userRepository.save(joiner);
        });
    }


    @Transactional
    public GroupCompleteRespDto completeGroup(Long uid, Long gid, MultipartFile image) throws Exception {
        LocalDateTime now = ZonedDateTime.of(LocalDateTime.now(), ZoneId.of("Asia/Seoul")).toLocalDateTime();
        // ?????? ???????????? ?????? ?????? ?????? ????????????
        if(!groupRepository.existsByIdAndMaker_Id(gid, uid)){
            throw NotFoundException.builder()
                    .httpStatus(HttpStatus.UNAUTHORIZED)
                    .message("Group ???????????? ????????????.")
                    .build();
        }
        Group group = groupRepository.findGroupByGid(gid);
        String groupMakerName = group.getMaker().getName();

        String imageLink = amazonS3Uploader.saveFileAndGetUrl(image);
        if (Optional.ofNullable(group.getMeetingAt()).isEmpty()) {
            group.updateNullMeetingAt(now);
        }

        // ???????????? group ?????? ?????? ???????????? ????????? ?????? ??????, ????????? badged -> true ??? ??????
        List<User> groupJoinUsers = userGroupRepository.findGroupJoiner(gid);

        List<GroupCompleteRespDto.Card> cardList = new ArrayList<>();

        groupJoinUsers.forEach(albumOwner -> {
            List<Long> metUsersIdList = albumRepository.findAllByOwner_Id(albumOwner.getId()).stream()
                    .map(Album::getMetUser).map(User::getId).collect(Collectors.toList());

            Long albumOwnerPoint = 0L;
            StringBuilder albumOwnerPointHistoryMessage = new StringBuilder();

            switch (userGroupRepository.countAllByJoiner_IdAndGroup_State(albumOwner.getId(), State.DONE)) {
                case 0:
                    albumOwnerPoint += 300L;
                    albumOwnerPointHistoryMessage.append("?????? ??????: 300???\n");
                    break;
                case 1:
                    albumOwnerPoint += 400L;
                    albumOwnerPointHistoryMessage.append("?????? ??????: 400???\n");
                    break;
                case 2:
                    albumOwnerPoint += 500L;
                    albumOwnerPointHistoryMessage.append("?????? ??????: 500???\n");
                    break;
                default:
                    albumOwnerPoint += 0L;
                    albumOwnerPointHistoryMessage.append("?????? ??????: 0???\n");
                    break;
            }
            albumOwnerPointHistoryMessage.append("?????? ??????: ");
            log.info("Initial point: Add {} ({})", albumOwnerPoint, albumOwner.getName());


            for (User groupJoinUser : groupJoinUsers) {
                userGroupRepository.updateFrontCardImg(groupJoinUser.getImageLink(), gid, groupJoinUser.getId());

                if (!Objects.equals(albumOwner.getId(), groupJoinUser.getId())) {
                    if (!metUsersIdList.contains(groupJoinUser.getId())) {
                        log.info("albumOwner : {}, joiner : {}", albumOwner.getName(), groupJoinUser.getName());
                        log.info("New card created: Add 100 point to {} ({})", albumOwnerPoint, albumOwner.getName());
                        albumOwnerPointHistoryMessage.append(groupJoinUser.getName()).append(" 100???, ");
                        albumOwnerPoint += 100;

                        albumRepository.save(Album.builder()
                                .owner(albumOwner).updatedAt(now).metUser(groupJoinUser).isBadged(true)
                                .build());
                    } else {
                        log.info("Card already exist: Add 50 point to {} ({})", albumOwnerPoint, albumOwner.getName());
                        albumOwnerPointHistoryMessage.append(groupJoinUser.getName()).append(" 50???, ");
                        albumOwnerPoint += 50;
                        albumRepository.updateBadgeState(true, now, groupJoinUser.getId(), albumOwner.getId());
                    }
                }
            }
            List<String> nameList = groupJoinUsers.stream()
                    .filter(user -> !user.getId().equals(albumOwner.getId()))
                    .map(User::getName).collect(Collectors.toList());

            notificationRepository.save(
                    Notification
                            .builder()
                            .sender(group.getMaker())
                            .receiver(albumOwner)
                            .content(String.join(", ", nameList) + "????????? ????????? ???????????????.")
                            .reason("?????? ?????????: " + groupMakerName + "???")
                            .build()
            );
            albumOwner.notifyUser();
            userRepository.save(albumOwner);

            albumOwnerPointHistoryMessage.replace(albumOwnerPointHistoryMessage.length() - 2, albumOwnerPointHistoryMessage.length(), "\n");
            albumOwnerPointHistoryMessage.append("??? ??????: ").append(albumOwnerPoint).append("???");

            pointHistoryRepository.save(
                    PointHistory
                            .builder()
                            .point(albumOwnerPoint)
                            .title(group.getName())
                            .message(albumOwnerPointHistoryMessage.toString())
                            .owner(albumOwner)
                            .build()
            );

            userRepository.addUserPoint(albumOwner.getId(), albumOwnerPoint);

        });

        groupRepository.updateCompleteGroup(imageLink, now, gid, State.DONE);

        for (User albumOwner : groupJoinUsers) {
            if (!albumOwner.getId().equals(uid)) {
                List<UserGroup> onePersonCard = userGroupRepository.findOnePersonCard(uid, albumOwner.getId(), State.DONE);
                cardList.add(GroupCompleteRespDto.Card.builder()
                        .userId(albumOwner.getId())
                        .username(albumOwner.getName())
                        .meetingAt(group.getMeetingAt())
                        .meetingCnt(Long.valueOf(onePersonCard.size()))
                        .frontImage(albumOwner.getImageLink())
                        .backImage(imageLink)
                        .build());
            }
        }
        return GroupCompleteRespDto.builder()
                .cardList(cardList)
                .build();

    }

    public DataResponseDto<List<GroupInfoResponseDto>> searchGroupByKeyword(String keyword) {
        List<GroupInfoResponseDto> resultDtoList = groupRepository
                .searchGroupByKeyword(keyword)
                .stream().map(x -> GroupInfoResponseDto.from(x, getHashtagStringList(x)))
                .collect(Collectors.toList());

        return DataResponseDto.<List<GroupInfoResponseDto>>builder()
                .data(resultDtoList).build();
    }

    public DataResponseDto<List<GroupInfoResponseDto>> searchAndSortGroupByKeyword(String keyword, SortedBy sortedBy) {
        List<GroupInfoResponseDto> resultDtoList;
        if (sortedBy == SortedBy.RECENT) {
            resultDtoList = groupRepository
                    .searchGroupByKeyword(keyword)
                    .stream().map(x -> GroupInfoResponseDto.from(x, getHashtagStringList(x)))
                    .collect(Collectors.toList());
        } else if (sortedBy == SortedBy.SOON) {
            resultDtoList = groupRepository
                    .searchGroupByMeetingAtAndKeyword(keyword)
                    .stream().map(x -> GroupInfoResponseDto.from(x, getHashtagStringList(x)))
                    .collect(Collectors.toList());
        } else {
            throw InvalidParameterException
                    .builder()
                    .httpStatus(HttpStatus.BAD_REQUEST)
                    .message("Invalid sortedBy parameter")
                    .build();
        }

        return DataResponseDto.<List<GroupInfoResponseDto>>builder()
                .data(resultDtoList).build();
    }


    @Transactional
    public DataResponseDto<CommentInfoResponseDto> writeComment(UserAccount userAccount, Long groupId, WritingCommentRequestDto writingCommentRequestDto) {
        User existingUser = validateUser(userAccount.getUserId());
        Group existingGroup = validateGroup(groupId);

        Comment comment = Comment.builder()
                .group(existingGroup)
                .user(existingUser)
                .content(writingCommentRequestDto.getContent())
                .build();

        commentRepository.save(comment);

        return DataResponseDto.<CommentInfoResponseDto>builder()
                .data(CommentInfoResponseDto.from(comment))
                .build();
    }

    @Transactional
    public DataResponseDto<CommentInfoResponseDto> updateComment(UserAccount userAccount, Long commentId, WritingCommentRequestDto writingCommentRequestDto) {
        User existingUser = validateUser(userAccount.getUserId());
        Comment existingComment = validateComment(commentId);

        if (!existingComment.getUser().getId().equals(existingUser.getId())) {
            throw InvalidParameterException
                    .builder()
                    .httpStatus(HttpStatus.FORBIDDEN)
                    .message("?????? ???????????? ????????????.")
                    .build();
        }

        existingComment.modifyContent(writingCommentRequestDto.getContent());

        commentRepository.save(existingComment);

        return DataResponseDto.<CommentInfoResponseDto>builder()
                .data(CommentInfoResponseDto.from(existingComment))
                .build();
    }

    @Transactional
    public DataResponseDto<CommentInfoResponseDto> deleteComment(UserAccount userAccount, Long commentId) {
        User existingUser = validateUser(userAccount.getUserId());
        Comment existingComment = validateComment(commentId);

        if (!existingComment.getUser().getId().equals(existingUser.getId())) {
            throw InvalidParameterException
                    .builder()
                    .httpStatus(HttpStatus.FORBIDDEN)
                    .message("?????? ???????????? ????????????.")
                    .build();
        }

        commentRepository.delete(existingComment);

        return DataResponseDto.<CommentInfoResponseDto>builder()
                .data(CommentInfoResponseDto.from(existingComment))
                .build();
    }

    private CommentInfoResponseDto getRecentCommentDtoFromGroup(Group group) {
        Optional<Comment> recentComment = commentRepository.findTopByGroupOrderByIdDesc(group);
        if (recentComment.isEmpty()) {
            return null;
        }
        return CommentInfoResponseDto.from(recentComment.get());
    }

    public DataResponseDto<Page<CommentInfoResponseDto>> getCommentList(Long groupId, Long cursor, Pageable pageable) {
        Group existingGroup = validateGroup(groupId);

        Page<Comment> commentPage = commentRepository.findAllByGroupAndIdGreaterThanEqual(existingGroup, cursor, pageable);;

        return DataResponseDto.<Page<CommentInfoResponseDto>>builder()
                .data(commentPage.map(CommentInfoResponseDto::from))
                .build();
    }
}
